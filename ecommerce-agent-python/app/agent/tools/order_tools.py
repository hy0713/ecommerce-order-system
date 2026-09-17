"""订单查询工具：按订单号（或主键 ID）查状态、金额、收货地址、创建时间"""
import contextvars
import json
import logging

import httpx
from langchain_core.tools import tool

from app.agent.tools.ecom_client import ecom_client

logger = logging.getLogger("app.tools")

# 请求级上下文：承载前端传来的电商登录 token
ecom_token_ctx: contextvars.ContextVar[str | None] = contextvars.ContextVar("ecom_token", default=None)

# 分页兜底扫描的最大页数与每页条数（避免只扫第一页导致「订单不存在」误判）
MAX_SCAN_PAGES = 5
PAGE_SIZE = 50


@tool("query_order_status")
async def query_order_status(order_no: str) -> str:
    """查询订单状态：根据订单号查询订单的状态、总金额、收货地址、创建时间等详细信息，返回结构化文本。
    参数：order_no - 订单号（字符串）"""
    token = ecom_token_ctx.get()
    if not token:
        return "您还没有登录电商账号，无法查询订单，请先登录后再查询订单状态。"
    key = str(order_no).strip()
    if not key:
        return "请提供要查询的订单号。"

    try:
        target = await _find_order(key, token)
    except httpx.HTTPError as e:
        logger.warning("订单查询网络异常: %s", e)
        return "订单查询服务暂时不可用，请稍后重试。"

    if target is None:
        return f"未找到订单号为 {key} 的订单，请确认订单号是否正确。"
    return _format_order(target)


async def _find_order(key: str, token: str) -> dict | None:
    """先按主键直查（命中即返回），未命中再分页扫描订单号"""
    # 1. 主键直查：订单号本身也是雪花 ID，若用户给出的是主键可直接命中
    if key.isdigit():
        result = await ecom_client.get(f"/api/order/{key}", token=token)
        if result.get("code") == 200 and isinstance(result.get("data"), dict):
            return result["data"]

    # 2. 分页扫描：按 orderNo / id 匹配，最多扫 MAX_SCAN_PAGES 页
    for page_num in range(1, MAX_SCAN_PAGES + 1):
        result = await ecom_client.get("/api/order/page", token=token,
                                       params={"pageNum": page_num, "pageSize": PAGE_SIZE})
        if result.get("code") != 200:
            # 401 等鉴权问题直接返回 None，由上层给出统一提示
            logger.info("订单分页查询返回非 200：code=%s", result.get("code"))
            return None
        data = result.get("data") or {}
        records = data.get("records") or []
        for record in records:
            if str(record.get("orderNo")) == key or str(record.get("id")) == key:
                return record
        # 已到最后一页
        total_pages = data.get("pages") or 0
        if not records or (total_pages and page_num >= total_pages):
            break
    return None


def _format_order(target: dict) -> str:
    items = target.get("items") or []
    item_desc = "；".join(
        f"{it.get('productName')} × {it.get('productQuantity')}（单价 {it.get('productPrice')} 元）"
        for it in items[:5]) or "无商品明细"
    return json.dumps({
        "订单号": target.get("orderNo"),
        "状态": target.get("orderStatusDesc"),
        "总金额": f"{target.get('totalAmount')} 元",
        "收货人": target.get("receiverName"),
        "收货地址": target.get("receiverAddress"),
        "创建时间": str(target.get("createTime", ""))[:19],
        "商品明细": item_desc,
    }, ensure_ascii=False)
