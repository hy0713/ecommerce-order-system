"""消息服务：Redis 上下文缓存（连续性事实源）+ MySQL 异步落库（归档层，MQ 优先、降级直写）"""
import json
import logging

from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.database.models import AgentMessage
from app.database.mq import LOG_EXCHANGE, LOG_ROUTING_KEY, mq_manager
from app.database.redis import redis_client
from app.services.session_service import ctx_key

logger = logging.getLogger("app.message")

_settings = get_settings()
CTX_MAX_ENTRIES = _settings.ctx_turns * 2 + 1      # 5 轮 × 2 + 余量
CTX_TTL_SECONDS = 24 * 60 * 60                     # 24 小时


def _entry(role: str, content: str) -> str:
    return json.dumps({"role": role, "content": content}, ensure_ascii=False)


def parse_entries(raw_list: list[str]) -> list[dict]:
    entries = []
    for raw in raw_list:
        try:
            e = json.loads(raw)
            if isinstance(e, dict) and "role" in e and "content" in e:
                entries.append(e)
        except (json.JSONDecodeError, TypeError):
            continue
    return entries


async def read_context(session_id: int) -> list[dict]:
    """读最近 N 轮对话上下文（超出 MAX_HISTORY_CHARS 截断早期内容）"""
    try:
        raw = await redis_client.lrange(ctx_key(session_id), -CTX_MAX_ENTRIES, -1)
        entries = parse_entries(raw)
    except Exception as e:
        logger.warning("读取 Redis 上下文失败: %s", e)
        return []
    # 字符预算：从头截断超预算的早期轮次
    total, keep = 0, []
    for entry in reversed(entries):
        total += len(entry["content"])
        if total > _settings.max_history_chars:
            break
        keep.append(entry)
    return list(reversed(keep))


async def write_context(session_id: int, role: str, content: str) -> None:
    """写回上下文：追加 1 条 → 截断 → 续 TTL。失败仅记日志（LLM 结果优先）"""
    try:
        pipe = redis_client.pipeline()
        pipe.rpush(ctx_key(session_id), _entry(role, content))
        pipe.ltrim(ctx_key(session_id), -CTX_MAX_ENTRIES, -1)
        pipe.expire(ctx_key(session_id), CTX_TTL_SECONDS)
        await pipe.execute()
    except Exception as e:
        logger.warning("写入 Redis 上下文失败: %s", e)


async def persist_messages(db: AsyncSession, session_id: int,
                           user_content: str, assistant_content: str, tool_calls_json: str | None) -> None:
    """对话落库：优先 MQ 异步，MQ 不可用降级同步直写 MySQL"""
    payload = {
        "session_id": session_id,
        "user_content": user_content,
        "assistant_content": assistant_content,
        "tool_calls": tool_calls_json,
    }
    if mq_manager.publish(LOG_EXCHANGE, LOG_ROUTING_KEY, payload):
        return
    logger.warning("MQ 不可用，对话日志降级直写 MySQL")
    await _direct_insert(db, payload)


async def _direct_insert(db: AsyncSession, payload: dict) -> None:
    db.add_all([
        AgentMessage(session_id=payload["session_id"], role="user", content=payload["user_content"]),
        AgentMessage(session_id=payload["session_id"], role="assistant", content=payload["assistant_content"],
                     tool_calls=payload.get("tool_calls")),
    ])
    await db.commit()


async def mq_log_handler(payload: dict) -> None:
    """MQ 消费者：对话日志落库"""
    from app.database.mysql import AsyncSessionLocal
    async with AsyncSessionLocal() as db:
        await _direct_insert(db, payload)
