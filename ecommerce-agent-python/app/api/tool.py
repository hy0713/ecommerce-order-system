"""工具配置管理接口：列表 + 状态启停（动态开关即时生效）"""
from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.common.enums import ToolStatus
from app.common.exceptions import BusinessException, ParamException
from app.common.identity import required_admin
from app.common.result import Result
from app.database.mysql import get_db
from app.database.models import AgentToolConfig
from app.schemas.tool import ToolStatusUpdate, ToolVO

router = APIRouter(prefix="/api/agent/tool", tags=["工具配置管理"])


@router.get("/list")
async def list_tools(_admin: int = Depends(required_admin),
                     db: AsyncSession = Depends(get_db)):
    """获取工具配置列表"""
    rows = (await db.execute(
        select(AgentToolConfig).order_by(AgentToolConfig.id.asc()))).scalars().all()
    return Result.ok([ToolVO.model_validate(r, from_attributes=True) for r in rows])


@router.put("/status/{tool_id}")
async def update_tool_status(tool_id: int, body: ToolStatusUpdate,
                             _admin: int = Depends(required_admin),
                             db: AsyncSession = Depends(get_db)):
    """开关工具状态（0禁用 1启用）"""
    if body.status not in (ToolStatus.DISABLED, ToolStatus.ENABLED):
        raise ParamException("status 仅支持 0（禁用）/ 1（启用）")
    row = await db.get(AgentToolConfig, tool_id)
    if row is None:
        raise BusinessException("工具不存在", code=404)
    row.status = body.status
    await db.commit()
    return Result.ok()
