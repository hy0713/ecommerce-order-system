"""系统 Prompt 模板与上下文拼装"""
import json

# 系统指令：角色定位 + 工具规则 + 知识引用规则 + 输出约束
SYSTEM_PROMPT = """你是「轻量电商」的智能客服助手，一名专业、耐心、严谨的电商客服。

【行为准则】
1. 回答用户问题时优先基于提供给你的知识库内容（售前售后政策、商品说明等），引用知识库时说明信息来源。
2. 用户询问订单状态、商品价格库存、售后政策时，主动使用可用工具查询真实数据，不要凭空编造。
3. 工具返回的数据是权威依据；工具不可用或未返回结果时，如实告知用户"暂时无法查询"，不要猜测。
4. 回答简洁清晰，使用中文，适当分点。涉及金额、库存、订单号等数字信息必须准确。
5. 不得编造不存在的订单号、商品、政策条款。

【知识库引用格式】
引用知识库内容时，在回答末尾标注「（来源：知识库文档）」。
"""

# 聊天上下文（最后 5 轮）按此格式拼装
HISTORY_TEMPLATE = "【{role}】{content}"


def build_history_block(entries: list[dict]) -> str:
    """对话历史 → 文本块；role 中文化"""
    if not entries:
        return ""
    lines = []
    for e in entries:
        role = "用户" if e.get("role") == "user" else "助手"
        lines.append(HISTORY_TEMPLATE.format(role=role, content=e.get("content", "")))
    return "\n".join(lines)


def build_knowledge_block(hits: list[dict]) -> str:
    """RAG 命中片段 → 文本块（带文档来源提示）"""
    if not hits:
        return ""
    parts = []
    for h in hits:
        parts.append(f"【知识片段（{hit_label(h)}）】{h['text']}")
    return "\n\n".join(parts)


def hit_label(hit: dict) -> str:
    """命中来源标签：向量路给相似度，关键词路给「关键词命中」，融合命中标注两者。

    关键词路没有余弦相似度（BM25 分数量纲不同、不可比），
    硬填一个 0 会让模型误判为「低相关」，因此单独标注。
    """
    source = hit.get("source", "vector")
    score = hit.get("score")
    if source == "keyword":
        return "关键词命中"
    if source == "hybrid":
        return f"混合命中，相似度 {score}" if score is not None else "混合命中"
    return f"相似度 {score}" if score is not None else "向量召回"


def build_user_input(question: str, knowledge_block: str = "", history_block: str = "") -> str:
    """最终用户输入：知识上下文 + 历史 + 当前问题"""
    sections = []
    if knowledge_block:
        sections.append(f"【知识库内容】\n{knowledge_block}\n（注：以上内容来自知识库，回答时优先参考）")
    if history_block:
        sections.append(f"【对话历史】\n{history_block}")
    sections.append(f"【当前问题】\n{question}")
    return "\n\n".join(sections)


def serialize_tool_calls(steps) -> str | None:
    """AgentExecutor intermediate_steps → tool_calls JSON 字符串"""
    calls = []
    for action, _ in (steps or []):
        calls.append({"tool": getattr(action, "tool", ""), "input": getattr(action, "tool_input", {})})
    return json.dumps(calls, ensure_ascii=False) if calls else None
