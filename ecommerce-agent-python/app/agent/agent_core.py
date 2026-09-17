"""Agent 核心调度服务：上下文组装 → RAG 检索 → 工具推理 → 答案生成 → 持久化

执行流程（严格按规格顺序）：
1. 会话校验（存在 + 归属）     2. 首问生成标题
3. Redis 读取最近 5 轮上下文   4. RAG 向量检索（Top3, ≥0.7）
5. Prompt 拼装                6. AgentExecutor 推理（可工具调用）
7. 工具执行回喂               8. 答案生成
9. Redis 上下文同步更新 + MQ 异步落库   10. 统一返回

容错：工具内部 catch-all → 友好 observation；executor max_iterations=4；
最外层兜底话术，保证任何情况下 HTTP 200 有回复。
"""
import asyncio
import logging
from typing import Optional

from langchain.agents import AgentExecutor, create_openai_tools_agent
from langchain_openai import ChatOpenAI
from sqlalchemy.ext.asyncio import AsyncSession

from app.agent.prompt import (
    SYSTEM_PROMPT, build_history_block, build_knowledge_block,
    build_user_input, serialize_tool_calls,
)
from app.agent.rag.embedding_service import embedding_service
from app.agent.rag.vector_store import hybrid_search
from app.agent.tools import get_enabled_tools
from app.agent.tools.order_tools import ecom_token_ctx
from app.common.exceptions import BusinessException
from app.config import get_settings
from app.database.models import AgentSession
from app.schemas.chat import ChatResponse, ToolCallInfo
from app.services import message_service, session_service

logger = logging.getLogger("app.agent")

_settings = get_settings()

_llm: Optional[ChatOpenAI] = None
_executor: Optional[AgentExecutor] = None
_tools_cache: list = []

FALLBACK_ANSWER = "抱歉，我暂时无法回答这个问题，请稍后再试。"


def get_llm() -> ChatOpenAI:
    global _llm
    if _llm is None:
        _llm = ChatOpenAI(
            model=_settings.llm_model,
            base_url=_settings.llm_base_url,
            api_key=_settings.llm_api_key,
            temperature=_settings.llm_temperature,
            max_tokens=_settings.llm_max_tokens,
            timeout=_settings.llm_timeout,
            max_retries=_settings.llm_max_retries,
        )
    return _llm


def get_executor(tools: list) -> AgentExecutor:
    """按当前启用工具构建/更新 Agent（工具启停即时生效）"""
    global _executor, _tools_cache
    if _executor is None or tools != _tools_cache:
        agent = create_openai_tools_agent(get_llm(), tools, _build_prompt())
        _executor = AgentExecutor(
            agent=agent,
            tools=tools,
            max_iterations=4,
            handle_parsing_errors="工具调用解析失败，请直接根据已有信息回答。",
            return_intermediate_steps=True,
            verbose=False,
        )
        _tools_cache = tools
    return _executor


def _build_prompt():
    """Agent 提示词模板：create_openai_tools_agent 要求含 input 与 agent_scratchpad 变量
    （历史上下文已拼进 input 文本，无需额外 placeholder）"""
    from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
    return ChatPromptTemplate.from_messages([
        ("system", SYSTEM_PROMPT),
        ("human", "{input}"),
        MessagesPlaceholder(variable_name="agent_scratchpad"),
    ])


async def chat(db: AsyncSession, session_id: int, user_id: int,
               content: str, ecom_token: Optional[str]) -> ChatResponse:
    # 1. 会话校验（存在 + 归属）
    sess = await session_service.get_session(db, session_id, user_id)

    # 2. 首问生成标题（前 20 字）
    await session_service.update_title(db, session_id, content)

    # 3. Redis 最近 N 轮上下文
    history = await message_service.read_context(session_id)

    # 4. 混合检索（BM25 + 向量，RRF 融合；失败静默跳过知识上下文）
    knowledge_hits = []
    try:
        query_vec = await asyncio.to_thread(embedding_service.embed_query, content)
        knowledge_hits = await asyncio.to_thread(
            hybrid_search, content, query_vec,
            _settings.rag_top_k, _settings.rag_min_score)
    except Exception as e:
        logger.warning("RAG 检索跳过: %s", e)

    # 5. Prompt 拼装
    prompt = build_user_input(
        content,
        knowledge_block=build_knowledge_block(knowledge_hits),
        history_block=build_history_block(history),
    )

    # 6-8. Agent 推理（工具调用由模型自主决定）
    answer = FALLBACK_ANSWER
    tool_calls: list[ToolCallInfo] = []
    steps = None
    token = ecom_token_ctx.set(ecom_token)
    try:
        executor = get_executor(await get_enabled_tools(db))
        result = await executor.ainvoke({"input": prompt})
        answer = str(result.get("output") or "").strip() or FALLBACK_ANSWER
        steps = result.get("intermediate_steps")
    except Exception as e:
        logger.exception("Agent 推理失败，返回兜底话术: %s", e)
    finally:
        ecom_token_ctx.reset(token)

    if steps:
        tool_calls_json = serialize_tool_calls(steps)
        for action, _ in steps:
            tool_calls.append(ToolCallInfo(tool=getattr(action, "tool", ""),
                                           input=getattr(action, "tool_input", {})))
    else:
        tool_calls_json = None

    # 9a. Redis 上下文同步更新（失败仅记日志）
    await message_service.write_context(session_id, "user", content)
    await message_service.write_context(session_id, "assistant", answer)

    # 9b. MQ 异步落库（不可用降级直写）
    await message_service.persist_messages(db, session_id, content, answer, tool_calls_json)

    # 10. 返回
    return ChatResponse(session_id=session_id, answer=answer, tool_calls=tool_calls)
