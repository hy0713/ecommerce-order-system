"""工具配置 DTO"""
from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field

from app.common.types import SnowflakeId


class ToolVO(BaseModel):
    id: SnowflakeId
    tool_name: str
    tool_description: str
    params_schema: Optional[str] = None
    status: int
    create_time: Optional[datetime] = None
    update_time: Optional[datetime] = None


class ToolStatusUpdate(BaseModel):
    status: int = Field(..., description="0禁用 1启用")
