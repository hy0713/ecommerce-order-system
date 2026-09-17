"""商品库存查询工具：按商品名称或商品 ID 查询价格、库存、状态（白名单接口，免登录）"""
import json
import logging
from typing import Optional

from langchain_core.tools import tool

from app.agent.tools.ecom_client import ecom_client

logger = logging.getLogger("app.tools")


@tool("query_product_stock")
async def query_product_stock(product_name: Optional[str] = None, product_id: Optional[int] = None) -> str:
    """查询商品库存：根据商品名称或商品ID查询商品的价格、库存数量、上架状态，返回结构化文本。
    参数：product_name - 商品名称（模糊匹配）；product_id - 商品ID，两者至少传一个"""
    if not product_name and not product_id:
        return "请提供商品名称或商品ID后再查询。"
    try:
        if product_id is not None:
            # ID 查询：走实时详情接口（不读缓存，保证库存准确）
            result = await ecom_client.get(f"/api/product/{product_id}/fresh")
            if result.get("code") == 200 and result.get("data"):
                p = result["data"]
                return _format_product(p)
            return f"未找到商品ID为 {product_id} 的商品。"
        # 名称查询：分页模糊搜索
        result = await ecom_client.get("/api/product/page", params={"keyword": product_name, "status": 1,
                                                                    "pageNum": 1, "pageSize": 5})
        if result.get("code") != 200:
            return f"商品服务暂时不可用：{result.get('message', '未知错误')}，请稍后重试。"
        records = (result.get("data") or {}).get("records") or []
        if not records:
            return f"未找到名称包含「{product_name}」的在售商品。"
        return "\n".join(_format_product(p) for p in records[:5])
    except Exception as e:
        logger.exception("商品查询工具异常: %s", e)
        return "商品查询服务暂时不可用，请稍后重试。"


def _format_product(p: dict) -> str:
    status = "在售" if p.get("status") == 1 else "已下架"
    return json.dumps({
        "商品ID": p.get("id"),
        "名称": p.get("name"),
        "分类": p.get("categoryName"),
        "价格": f"{p.get('price')} 元",
        "库存": p.get("stock"),
        "状态": status,
    }, ensure_ascii=False)
