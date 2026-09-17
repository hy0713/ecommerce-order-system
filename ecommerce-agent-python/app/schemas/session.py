"""会话管理 DTO"""
from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field

from app.common.types import SnowflakeId


class SessionCreate(BaseModel):
    # user_id 已移除：会话归属由网关注入的 X-User-Id 决定，不接受自报
    title: str = Field("新会话", max_length=128, description="会话标题，默认新会话")


class SessionVO(BaseModel):
    # 出参用 SnowflakeId：序列化为字符串，避免前端 JS 精度丢失
    id: SnowflakeId
    user_id: SnowflakeId
    title: str
    status: int
    create_time: Optional[datetime]
    update_time: Optional[datetime]


class MessageVO(BaseModel):
    # 归档层（MySQL）已落库的消息带 id；由 Redis 热层补齐的「尚未落库」消息 id 为空
    id: Optional[SnowflakeId] = None
    session_id: SnowflakeId
    role: str
    content: str
    tool_calls: Optional[str] = None
    create_time: Optional[datetime] = None
    # True 表示该条仍在 MQ 异步落库途中，仅来自 Redis 热层
    pending: bool = False
