"""对话交互接口

身份来源：网关注入的 ``X-User-Id``（**可选鉴权**：无 token 的游客为 0）。
调用方自报的 user_id 一律忽略，避免冒充他人身份建会话。
"""
from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.agent.agent_core import chat as agent_chat
from app.common.identity import optional_user_id
from app.common.result import Result
from app.database.mysql import get_db
from app.schemas.chat import ChatRequest
from app.services import session_service

router = APIRouter(prefix="/api/agent/chat", tags=["对话交互"])


@router.post("")
async def send_chat(body: ChatRequest,
                    user_id: int = Depends(optional_user_id),
                    db: AsyncSession = Depends(get_db)):
    """发送消息并获取回答（session_id 为空时自动创建新会话）"""
    session_id = body.session_id
    if session_id is None:
        vo = await session_service.create_session(db, user_id, "新会话")
        session_id = vo.id
    resp = await agent_chat(db, session_id, user_id, body.content, body.ecom_token)
    return Result.ok(resp.model_dump())

