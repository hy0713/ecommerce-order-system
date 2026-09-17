"""工具注册表：内置 3 个工具，与 agent_tool_config 表联动（状态过滤 + 种子）"""
import json
import logging

from langchain_core.tools import BaseTool
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.agent.tools.aftersale_tools import query_after_sale_policy
from app.agent.tools.order_tools import query_order_status
from app.agent.tools.product_tools import query_product_stock
from app.common.enums import ToolStatus
from app.database.models import AgentToolConfig

logger = logging.getLogger("app.tools")

# 内置工具清单（用于种子与状态过滤）
BUILTIN_TOOLS: dict[str, BaseTool] = {
    t.name: t for t in (query_order_status, query_product_stock, query_after_sale_policy)
}


def _args_schema_json(tool: BaseTool) -> str | None:
    """参数 JSON Schema：langchain 0.1.0 的 args_schema 是 pydantic v1 模型（.schema()），
    兼容 v2（.model_json_schema()）"""
    s = getattr(tool, "args_schema", None)
    if s is None:
        return None
    fn = getattr(s, "model_json_schema", None) or getattr(s, "schema", None)
    if fn is None:
        return None
    try:
        return json.dumps(fn(), ensure_ascii=False)
    except Exception:
        return None


async def seed_tool_configs(db: AsyncSession) -> None:
    """启动时同步 3 个工具到配置表（已存在则跳过；同步描述与参数 Schema）"""
    for tool in BUILTIN_TOOLS.values():
        schema = _args_schema_json(tool)
        row = (await db.execute(
            select(AgentToolConfig).where(AgentToolConfig.tool_name == tool.name))).scalar_one_or_none()
        if row is None:
            db.add(AgentToolConfig(tool_name=tool.name, tool_description=tool.description,
                                   params_schema=schema, status=ToolStatus.ENABLED))
        else:
            row.tool_description = tool.description
            row.params_schema = schema
    await db.commit()
    logger.info("工具配置种子同步完成（%s 个）", len(BUILTIN_TOOLS))


async def get_enabled_tools(db: AsyncSession) -> list[BaseTool]:
    """返回启用状态的工具列表（Agent 只绑定 status=1 的工具，PUT 停用即时生效）"""
    rows = (await db.execute(
        select(AgentToolConfig).where(AgentToolConfig.status == ToolStatus.ENABLED))).scalars().all()
    tools = []
    for row in rows:
        t = BUILTIN_TOOLS.get(row.tool_name)
        if t is not None:
            tools.append(t)
    return tools
