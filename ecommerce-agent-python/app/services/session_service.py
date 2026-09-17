"""会话管理服务：CRUD + 首问生成标题 + 删除级联清理"""
import logging
from datetime import datetime

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.common.exceptions import BusinessException
from app.common.utils.snowflake import snowflake
from app.database.models import AgentMessage, AgentSession
from app.database.redis import redis_client
from app.schemas.session import MessageVO, SessionVO

logger = logging.getLogger("app.session")

DEFAULT_TITLE = "新会话"
CTX_KEY_PREFIX = "agent:ctx:"


def ctx_key(session_id: int) -> str:
    return f"{CTX_KEY_PREFIX}{session_id}"


async def create_session(db: AsyncSession, user_id: int, title: str = DEFAULT_TITLE) -> SessionVO:
    sess = AgentSession(user_id=user_id, title=title or DEFAULT_TITLE, status=1)
    db.add(sess)
    await db.commit()
    await db.refresh(sess)
    return SessionVO.model_validate(sess, from_attributes=True)


async def list_sessions(db: AsyncSession, user_id: int) -> list[SessionVO]:
    rows = (await db.execute(
        select(AgentSession).where(AgentSession.user_id == user_id, AgentSession.status == 1)
        .order_by(AgentSession.update_time.desc()))).scalars().all()
    return [SessionVO.model_validate(r, from_attributes=True) for r in rows]


async def get_session(db: AsyncSession, session_id: int, user_id: int | None = None) -> AgentSession:
    sess = await db.get(AgentSession, session_id)
    if sess is None:
        raise BusinessException("会话不存在", code=404)
    if user_id is not None and sess.user_id != user_id:
        raise BusinessException("无权访问该会话", code=403)
    return sess


async def delete_session(db: AsyncSession, session_id: int, user_id: int | None = None) -> None:
    sess = await get_session(db, session_id, user_id)
    await db.execute(delete(AgentMessage).where(AgentMessage.session_id == session_id))
    await db.delete(sess)
    await db.commit()
    try:
        await redis_client.delete(ctx_key(session_id))
    except Exception:
        logger.warning("删除会话 %s 的 Redis 上下文失败", session_id)


async def update_title(db: AsyncSession, session_id: int, content: str) -> None:
    """首问生成标题：取提问前 20 字"""
    sess = await db.get(AgentSession, session_id)
    if sess is None:
        return
    if sess.title == DEFAULT_TITLE and content.strip():
        sess.title = content.strip()[:20]
        sess.update_time = datetime.now()
        await db.commit()


async def list_messages(db: AsyncSession, session_id: int, user_id: int | None = None) -> list[MessageVO]:
    """获取会话历史消息。

    <p>数据来自两层，必须合并后才能保证「读己之写」：
    <ol>
      <li><b>归档层</b>：MySQL `agent_message`（由 MQ 消费者异步落库，可能滞后）；</li>
      <li><b>热层</b>：Redis 上下文 `agent:ctx:{session_id}`（在 chat 请求内同步写入，即「连续性事实源」）。</li>
    </ol>
    仅读 MySQL 会出现「刚发完消息，立刻刷新历史却是空的」——本方法用 Redis 补齐
    归档层尚未落库的尾部消息，并按内容匹配去重（`pending=True` 标记其为未归档）。
    """
    await get_session(db, session_id, user_id)
    rows = (await db.execute(
        select(AgentMessage).where(AgentMessage.session_id == session_id)
        .order_by(AgentMessage.create_time.asc(), AgentMessage.id.asc()))).scalars().all()
    messages = [MessageVO.model_validate(r, from_attributes=True) for r in rows]

    pending = await _pending_tail(session_id, messages)
    return messages + pending


async def _pending_tail(session_id: int, persisted: list[MessageVO]) -> list[MessageVO]:
    """从 Redis 热层取出「归档层尚未落库」的尾部消息"""
    from app.services import message_service

    try:
        recent = await message_service.read_context(session_id)
    except Exception:
        logger.warning("读取会话 %s 的 Redis 上下文失败，历史仅返回归档层数据", session_id)
        return []
    if not recent:
        return []
    persisted_pairs = [(m.role, m.content) for m in persisted]
    tail = _tail_after_persisted(persisted_pairs, [(e["role"], e["content"]) for e in recent])
    return [
        MessageVO(session_id=session_id, role=role, content=content, pending=True)
        for role, content in tail
    ]


def _tail_after_persisted(persisted: list[tuple[str, str]],
                          recent: list[tuple[str, str]]) -> list[tuple[str, str]]:
    """找出 recent（按时间正序）中排在 persisted 之后的那些条目。

    做法：在 recent 中定位与 persisted 尾部完全一致的连续片段，返回其后的部分。
    这样即使归档层只落后一两条也能精确补齐；若完全没有交集（例如归档层为空），
    则保守返回全部 recent。允许的最坏情况是短暂重复展示，绝不会丢消息。
    """
    if not recent:
        return []
    if not persisted:
        return recent
    max_k = min(len(persisted), len(recent))
    for k in range(max_k, 0, -1):
        suffix = persisted[-k:]
        # 优先取最近出现的位置，避免与更早的同内容轮次混淆
        for start in range(len(recent) - k, -1, -1):
            if recent[start:start + k] == suffix:
                return recent[start + k:]
    return recent
