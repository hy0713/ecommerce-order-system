"""Redis 连接：应用侧 async 客户端 + MQ 消费线程侧 sync 客户端"""
import redis as redis_sync
from redis.asyncio import Redis as AsyncRedis

from app.config import get_settings

_settings = get_settings()


def _kwargs():
    kw = dict(host=_settings.redis_host, port=_settings.redis_port, db=_settings.redis_db, decode_responses=True)
    if _settings.redis_password:
        kw["password"] = _settings.redis_password
    return kw


# 应用侧（async 事件循环）
redis_client: AsyncRedis = AsyncRedis(**_kwargs())

# MQ 消费线程侧（sync，向量检索/写入用；decode_responses=False 以保留 bytes 向量）
sync_redis: redis_sync.Redis = redis_sync.Redis(
    host=_settings.redis_host, port=_settings.redis_port, db=_settings.redis_db,
    password=_settings.redis_password or None, decode_responses=False,
)
# sync 检索结果转 str 用
sync_redis_str: redis_sync.Redis = redis_sync.Redis(
    host=_settings.redis_host, port=_settings.redis_port, db=_settings.redis_db,
    password=_settings.redis_password or None, decode_responses=True,
)
