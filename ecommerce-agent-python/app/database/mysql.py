"""MySQL 异步引擎与会话管理（SQLAlchemy 2.0 async + aiomysql）"""
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.config import get_settings

_settings = get_settings()

engine = create_async_engine(
    _settings.mysql_url,
    pool_size=10,
    max_overflow=20,
    pool_pre_ping=True,          # 取连接前探活，避免 MySQL 重启后连接失效
    pool_recycle=3600,
    echo=False,
)

# 兼容修复：aiomysql 方言复用 pymysql 的 do_ping（调用 ping() 无参），而 aiomysql 0.2.0
# 的 ping() 必须带 reconnect 参数。设置 _send_false_to_ping=True 使 do_ping 调用 ping(False)，
# 与 aiomysql 签名兼容，同时保留 pool_pre_ping 探活能力。
engine.dialect._send_false_to_ping = True

AsyncSessionLocal = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


async def get_db():
    """FastAPI 依赖：请求级会话"""
    async with AsyncSessionLocal() as session:
        yield session
