"""售后政策查询工具：内部调用 RAG 检索，从知识库匹配退换货/保修/退款规则"""
import asyncio
import logging

from langchain_core.tools import tool

from app.agent.rag.embedding_service import embedding_service
from app.agent.prompt import hit_label
from app.agent.rag.vector_store import hybrid_search
from app.config import get_settings

logger = logging.getLogger("app.tools")

_settings = get_settings()


@tool("query_after_sale_policy")
async def query_after_sale_policy(keyword: str) -> str:
    """查询售后政策：查询退换货、保修、退款等售后相关规则，返回知识库中匹配的政策原文。
    参数：keyword - 查询关键词（如：退货、换货、保修、退款、七天）"""
    try:
        # embedding 是 CPU 密集操作，放线程池避免阻塞事件循环
        query_vec = await asyncio.to_thread(embedding_service.embed_query, keyword)
        hits = await asyncio.to_thread(
            hybrid_search, keyword, query_vec,
            _settings.rag_top_k, _settings.rag_aftersale_min_score)
        if not hits:
            return f"知识库中未找到与「{keyword}」相关的售后政策，建议咨询人工客服。"
        parts = [f"【片段{idx + 1}（{hit_label(h)}）】{h['text'][:300]}"
                 for idx, h in enumerate(hits)]
        return "根据售后政策文档检索结果：\n" + "\n\n".join(parts)
    except Exception as e:
        logger.exception("售后政策查询工具异常: %s", e)
        return "售后政策知识库暂时不可用，请稍后重试。"
