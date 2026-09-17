"""对话交互 DTO"""
from typing import Optional
from pydantic import BaseModel, Field

from app.common.types import SnowflakeId


class ChatRequest(BaseModel):
    # user_id 已移除：身份只认网关注入的 X-User-Id（见 app/common/identity.py）。
    # 调用方仍可能带上该字段（老前端），Pydantic 默认忽略额外字段，不影响兼容。
    session_id: Optional[int] = Field(None, description="会话ID；为空时自动创建新会话")
    content: str = Field(..., min_length=1, max_length=4000, description="用户提问内容")
    ecom_token: Optional[str] = Field(None, description="电商系统登录 token（查询订单等需鉴权工具用）")


class ToolCallInfo(BaseModel):
    tool: str = Field(..., description="工具名")
    input: dict = Field(default_factory=dict, description="工具入参")


class ChatResponse(BaseModel):
    # 出参用 SnowflakeId：序列化为字符串，避免前端 JS 精度丢失
    session_id: SnowflakeId = Field(..., description="会话ID")
    answer: str = Field(..., description="助手回答")
    tool_calls: list[ToolCallInfo] = Field(default_factory=list, description="本轮工具调用记录")
