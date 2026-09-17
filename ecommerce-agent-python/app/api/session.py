"""会话管理接口

身份来源：网关注入的 ``X-User-Id``（见 app/common/identity.py），
**不由调用方自报** —— 否则任意用户可枚举 user_id 读取、删除他人会话。
"""
from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.common.identity import required_user_id
from app.common.result import Result
from app.database.mysql import get_db
from app.schemas.session import SessionCreate
from app.services import session_service

router = APIRouter(prefix="/api/agent/session", tags=["会话管理"])


@router.get("/list")
async def list_sessions(user_id: int = Depends(required_user_id),
                        db: AsyncSession = Depends(get_db)):
    """获取当前登录用户的会话列表"""
    return Result.ok(await session_service.list_sessions(db, user_id))


@router.post("/create")
async def create_session(body: SessionCreate,
                         user_id: int = Depends(required_user_id),
                         db: AsyncSession = Depends(get_db)):
    """创建新会话（归属当前登录用户）"""
    vo = await session_service.create_session(db, user_id, body.title)
    return Result.ok(vo)


@router.delete("/delete/{session_id}")
async def delete_session(session_id: int,
                         user_id: int = Depends(required_user_id),
                         db: AsyncSession = Depends(get_db)):
    """删除会话（级联清理消息与上下文缓存）。只能删除属于自己的会话。

    身份取自网关 token 校验结果，因此这里的归属校验是**真实的安全边界**：
    调用方无法通过改参数删除他人会话。
    """
    await session_service.delete_session(db, session_id, user_id)
    return Result.ok()


@router.get("/{session_id}/messages")
async def list_messages(session_id: int,
                        user_id: int = Depends(required_user_id),
                        db: AsyncSession = Depends(get_db)):
    """获取会话历史消息。只能读取属于自己的会话。"""
    return Result.ok(await session_service.list_messages(db, session_id, user_id))
