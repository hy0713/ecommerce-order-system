"""项目启动入口：FastAPI 实例初始化

启动流程：建表（create_all，可重试/不阻断）→ Redis 探测 → 向量索引幂等创建 → MQ 启停 → 工具种子

降级约定（与 README 保持一致）：
- Redis 不可用 → RAG 能力降级，对话仍可返回；
- RabbitMQ 不可用 → 消息改为降级直写，对话仍可返回；
- MySQL 不可用 → 依赖 MySQL 的接口返回 503，但**服务进程照常启动**，
  后台持续重试建表，数据库恢复后自动可用（`/health` 可查 mysql 状态）。
"""
import asyncio
import logging
import os

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import chat, knowledge, session, tool
from app.common.exceptions import register_exception_handlers
from app.common.result import Result
from app.config import get_settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [%(name)s] %(message)s")
logger = logging.getLogger("app.main")

_settings = get_settings()

# 启动期建表的最大重试次数与间隔（约 4s × 3 ≈ 12s，避免阻塞启动过久影响健康检查）
SCHEMA_MAX_ATTEMPTS = 3
SCHEMA_RETRY_INTERVAL_SECONDS = 2.0
# 后台持续重试间隔（数据库晚于服务启动时用）
SCHEMA_BACKGROUND_INTERVAL_SECONDS = 15.0

# 建表是否已成功（供 /health 观测）
_schema_ready = False
# 持有后台任务引用，避免被 GC 回收
_background_tasks: set = set()

app = FastAPI(
    title="电商智能客服 Agent 系统",
    description="RAG 检索增强 + 工具调用 的智能客服，对接电商微服务后端",
    version="1.0.0",
    docs_url="/docs",
)


def _cors_origins() -> list:
    """允许的前端来源，逗号分隔；默认放开（本地演示），生产用 CORS_ORIGINS 收敛"""
    raw = os.getenv("CORS_ORIGINS", "*")
    if raw.strip() == "*":
        return ["*"]
    return [origin.strip() for origin in raw.split(",") if origin.strip()]


app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_origins(),
    allow_methods=["*"],
    allow_headers=["*"],
    allow_credentials=False,
)

register_exception_handlers(app)

# 注册路由
app.include_router(session.router)
app.include_router(chat.router)
app.include_router(knowledge.router)
app.include_router(tool.router)


async def _ensure_schema() -> bool:
    """幂等建表；成功返回 True，异常仅记录不抛出"""
    global _schema_ready
    from app.database.models import Base
    from app.database.mysql import engine

    try:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        _schema_ready = True
        return True
    except Exception as e:
        logger.warning("数据库建表失败：%s", e)
        return False


async def _schema_retry_loop() -> None:
    """后台持续重试建表，直到成功（数据库晚于服务启动的场景）"""
    global _schema_ready
    from app.agent.tools import seed_tool_configs
    from app.database.models import Base
    from app.database.mysql import AsyncSessionLocal, engine

    while not _schema_ready:
        await asyncio.sleep(SCHEMA_BACKGROUND_INTERVAL_SECONDS)
        if _schema_ready:
            return
        try:
            async with engine.begin() as conn:
                await conn.run_sync(Base.metadata.create_all)
            _schema_ready = True
            logger.info("数据库已恢复，表结构就绪，服务转为正常模式")
            async with AsyncSessionLocal() as db:
                await seed_tool_configs(db)
            logger.info("工具配置种子补种完成")
        except Exception as e:
            logger.warning("后台建表重试仍失败：%s", e)


def _spawn(coro) -> None:
    task = asyncio.create_task(coro)
    _background_tasks.add(task)
    task.add_done_callback(_background_tasks.discard)


@app.on_event("startup")
async def on_startup():
    """初始化：建表（可重试，不阻断启动）→ Redis → 向量索引 → MQ → 工具种子"""
    from app.agent.rag.vector_store import ensure_index
    from app.agent.tools import seed_tool_configs
    from app.database.mysql import AsyncSessionLocal
    from app.database.mq import DOC_QUEUE, LOG_QUEUE, mq_manager
    from app.services.knowledge_service import mq_doc_handler
    from app.services.message_service import mq_log_handler

    # 1. 建表（MySQL initdb 只跑一次，故启动时幂等建表）
    #    MySQL 是硬依赖，但此处不阻断启动：失败则降级运行 + 后台重试，
    #    避免「数据库晚启动 → Agent 进程直接退出」。
    db_ready = False
    for attempt in range(1, SCHEMA_MAX_ATTEMPTS + 1):
        if await _ensure_schema():
            db_ready = True
            logger.info("数据库表结构就绪（agent_session/agent_message/knowledge_document/"
                        "agent_tool_config）")
            break
        if attempt == SCHEMA_MAX_ATTEMPTS:
            break
        await asyncio.sleep(SCHEMA_RETRY_INTERVAL_SECONDS)

    if not db_ready:
        logger.error("数据库不可用，服务以降级模式启动：依赖 MySQL 的接口将返回业务错误码 500"
                     "（HTTP 仍为 200，符合本项目统一返回约定），/health 可查 mysql 状态；"
                     "后台每 %.0f 秒重试建表", SCHEMA_BACKGROUND_INTERVAL_SECONDS)
        _spawn(_schema_retry_loop())

    # 2. Redis 探测 + 向量索引（失败不阻断启动，服务降级运行）
    try:
        from app.database.redis import redis_client
        await redis_client.ping()
        logger.info("Redis Stack 连接正常（:%s）", _settings.redis_port)
        await asyncio.to_thread(ensure_index)
    except Exception as e:
        logger.warning("Redis 不可用（RAG 降级跳过）: %s", e)

    # 3. MQ 启动 + 消费者注册（连接失败由 mq_manager 自行降级）
    loop = asyncio.get_running_loop()
    mq_manager.register_handler(DOC_QUEUE, mq_doc_handler)
    mq_manager.register_handler(LOG_QUEUE, mq_log_handler)
    mq_manager.start(loop)

    # 4. 工具种子（依赖 MySQL，失败不影响启动）
    if db_ready:
        try:
            async with AsyncSessionLocal() as db:
                await seed_tool_configs(db)
        except Exception as e:
            logger.warning("工具配置种子写入失败：%s", e)

    # 5. 大模型配置确认（脱敏打印）
    logger.info("LLM 配置: %s", _settings.safe_llm())


@app.on_event("shutdown")
async def on_shutdown():
    for task in list(_background_tasks):
        task.cancel()
    from app.agent.tools.ecom_client import ecom_client
    await ecom_client.aclose()
    from app.database.mq import mq_manager
    mq_manager.stop()
    from app.database.mysql import engine
    await engine.dispose()
    logger.info("服务已停止")


async def _check_mysql() -> bool:
    """MySQL 连通性探测"""
    from sqlalchemy import text
    from app.database.mysql import engine

    try:
        async with engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
        return True
    except Exception:
        return False


@app.get("/api/agent/health")
async def health():
    """探活接口：逐项上报依赖状态，避免「唯一的硬依赖没被监控」"""
    from app.database.redis import redis_client
    from app.database.mq import mq_manager

    redis_ok = False
    try:
        await redis_client.ping()
        redis_ok = True
    except Exception:
        pass

    mysql_ok = await _check_mysql()
    healthy = mysql_ok and redis_ok and mq_manager.healthy
    return Result.ok({
        "service": "ecommerce-agent-python",
        "healthy": healthy,
        "mysql": mysql_ok,
        "schemaReady": _schema_ready,
        "redis": redis_ok,
        "rabbitmq": mq_manager.healthy,
        "llm_model": _settings.llm_model,
    })
