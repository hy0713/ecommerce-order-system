#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""检索链路诊断：把「关键词路 / 向量路 / 融合后」三者的命中并排打出来。

用途：
1. 排查「明明上传了文档却召回不到」是分词问题还是阈值问题；
2. 直观确认混合检索是否生效（关键词路有候选、融合结果 source 标注为 hybrid）。

需要 Redis Stack 在线（向量索引 idx:agent_knowledge），MySQL 不需要。

用法：.venv/Scripts/python.exe scripts/diag_retrieval.py "七天无理由退货的运费"
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from app.agent.rag.embedding_service import embedding_service  # noqa: E402
from app.agent.rag import vector_store as vs  # noqa: E402
from app.config import get_settings  # noqa: E402

S = get_settings()
query = sys.argv[1] if len(sys.argv) > 1 else "七天无理由退货的运费怎么算"


def show(title, hits):
    print(f"\n--- {title}（{len(hits)} 条）---")
    if not hits:
        print("   （空）")
    for i, h in enumerate(hits, 1):
        score = h.get("score")
        score_txt = "—" if score is None else f"{score:.4f}"
        src = h.get("source", "?")
        text = str(h.get("text", "")).replace("\n", " ")[:60]
        print(f"   {i}. doc={h.get('doc_id')} chunk={h.get('chunk_index')} "
              f"sim={score_txt} source={src} | {text}")


print(f"查询：{query}")
print(f"配置：hybrid_enabled={S.rag_hybrid_enabled} top_k={S.rag_top_k} "
      f"min_score={S.rag_min_score} rrf_k={S.rrf_k} keyword_candidates={S.keyword_candidates}")

tokens = vs.text_to_tokens(query)
print(f"\n切词（{len(tokens)} 个）：{' '.join(tokens)}")

vec = embedding_service.embed_query(query)

kw = vs.keyword_search(query, S.keyword_candidates)
show("关键词路（BM25，@tokens 字段）", kw)

vc = vs.vector_candidates(vec, max(S.rag_top_k * 2, S.rag_top_k), S.rag_min_score)
show(f"向量路候选（余弦阈值 {S.rag_min_score}）", vc)

final = vs.hybrid_search(query, vec, S.rag_top_k, S.rag_min_score)
for h in final:
    h.setdefault("source", "vector")
show(f"融合后最终结果（TopK={S.rag_top_k}）", final)

print("\n结论：", end="")
sources = {h.get("source") for h in final}
if not final:
    print("两路都没有命中 —— 检查文档是否已向量化（status=2）或查询词是否过偏")
elif "hybrid" in sources:
    print("两路都命中且被融合（hybrid）—— 混合检索生效")
elif sources == {"keyword"}:
    print("仅关键词路命中 —— 向量路被相似度阈值挡住，混合检索在兜底")
elif sources == {"vector"}:
    print("仅向量路命中 —— 关键词路无候选（查询词可能不含可切分的实词）")
else:
    print("结果来源：", sources)
