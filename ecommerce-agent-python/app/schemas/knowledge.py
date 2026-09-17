"""知识库 DTO"""
from datetime import datetime
from typing import Any, Optional
from pydantic import BaseModel, Field

from app.common.types import SnowflakeId


class DocVO(BaseModel):
    id: SnowflakeId
    title: str
    doc_type: str
    file_url: str
    chunk_count: int
    status: int
    status_desc: Optional[str] = None
    fail_reason: Optional[str] = None
    create_time: Optional[datetime] = None


class PageResult(BaseModel):
    records: list[Any] = Field(default_factory=list)
    # total/分页参数保持数字：前端分页组件依赖数值
    total: int = 0
    page_num: int = 1
    page_size: int = 10
