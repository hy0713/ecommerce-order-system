#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""混合检索离线校验（不需要 Redis / MySQL 在线）

覆盖：切词（中英混合）→ 关键词查询构造 → RRF 融合数学 → 融合编排与去重 →
展示标签。用打桩替换 Redis 客户端，因此可在任何环境跑。

用法：.venv/Scripts/python.exe scripts/test_hybrid_retrieval.py
"""
import sys
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from app.agent.rag import vector_store as vs  # noqa: E402
from app.agent.prompt import hit_label  # noqa: E402

PASS = 0
FAIL = 0


def check(name, actual, expected):
    global PASS, FAIL
    if actual == expected:
        print(f"  PASS  {name}")
        PASS += 1
    else:
        print(f"  FAIL  {name}\n        期望 {expected!r}\n        实际 {actual!r}")
        FAIL += 1


def ok(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        print(f"  PASS  {name}")
        PASS += 1
    else:
        print(f"  FAIL  {name}  {detail}")
        FAIL += 1


print("== 1. 切词：ASCII 词 + 中文二元组 ==")
check("英文词保留、1~2 位数字剔除", vs.text_to_tokens("iPhone 15 Pro"), ["iphone", "pro"])
check("3 位以上数字保留（订单号/政策编号）", vs.text_to_tokens("SF-2026-0916"),
      ["sf", "2026", "0916"])
check("短数字剔除避免误召回（7 天 / 24 小时）", vs.text_to_tokens("7 天 24 小时"), ["天", "小时"])
check("中文四字 → 3 个 bigram", vs.text_to_tokens("售后政策"), ["售后", "后政", "政策"])
check("中英混合", vs.text_to_tokens("退货 iPhone"),
      ["iphone", "退货"])
check("单字中文单独保留", vs.text_to_tokens("退"), ["退"])
check("去重且保序（跨空格不生成 bigram）", vs.text_to_tokens("退货 退货"), ["退货"])
check("空文本", vs.text_to_tokens(""), [])
check("纯标点无 token", vs.text_to_tokens("！@#￥%……"), [])
# 关键约束：token 只含字母/数字/汉字 —— 保证拼进 RediSearch 查询无需转义
_all_safe = all(t.isalnum() for t in vs.text_to_tokens('a-b*c(d) "e" |f| 售后政策！'))
ok("切出的 token 均为字母数字汉字（无需查询转义）", _all_safe)

print("\n== 2. 关键词查询构造（打桩捕获真实命令） ==")
captured = {}


def fake_exec(*args, **kwargs):
    captured["args"] = args
    # FT.SEARCH 返回格式：count, key, [field, value, ...], ...
    return [2,
            b"knowledge:vector:1:0", [b"doc_id", b"1", b"chunk_index", b"0",
                                      b"start_pos", b"0", b"text", b"\xe9\x80\x80\xe8\xb4\xa7\xe8\xa7\x84\xe5\x88\x99"],
            b"knowledge:vector:2:0", [b"doc_id", b"2", b"chunk_index", b"0",
                                      b"start_pos", b"10", b"text", b"\xe4\xbf\x9d\xe4\xbf\xae"]]


with patch.object(vs.sync_redis, "execute_command", side_effect=fake_exec):
    hits = vs.keyword_search("售后政策", limit=7)

cmd = [a.decode() if isinstance(a, bytes) else str(a) for a in captured["args"]]
check("命令是 FT.SEARCH", cmd[0], "FT.SEARCH")
check("索引名", cmd[1], vs.INDEX_NAME)
check("查询串命中 tokens 字段", cmd[2].startswith("@tokens:("), True)
check("查询串内容为 bigram OR", cmd[2], "@tokens:(售后|后政|政策)")
check("LIMIT 使用了传入上限", "7" in cmd, True)
check("未使用向量 DIALECT（关键词路不需要）", "DIALECT" in cmd, False)
check("解析出 2 条命中", len(hits), 2)
check("关键词路不带 distance", "distance" in hits[0], False)
check("关键词路标记来源", hits[0]["source"], "keyword")
check("空查询不触发检索", vs.keyword_search("！@#"), [])

print("\n== 3. RRF 融合数学 ==")
# doc A：向量第 1、关键词第 3；doc B：两路都第 2；doc C：仅关键词第 1
A = {"key": "A", "doc_id": 1, "chunk_index": 0, "source": "vector"}
B = {"key": "B", "doc_id": 2, "chunk_index": 0, "source": "vector"}
C = {"key": "C", "doc_id": 3, "chunk_index": 0, "source": "vector"}
A2 = {"key": "A", "doc_id": 1, "chunk_index": 0, "source": "keyword"}
B2 = {"key": "B", "doc_id": 2, "chunk_index": 0, "source": "keyword"}
C2 = {"key": "C", "doc_id": 3, "chunk_index": 0, "source": "keyword"}
fused = vs.rrf_fuse([(1.0, [A, B, C2]), (1.0, [C, B2, A2])], k=60)
order = [h["doc_id"] for h in fused]

k = 60
# 实现里 rrf_score 刻意保留 6 位小数（对外展示用），因此断言容差取 1e-6
TOL = 1e-6
exp_A = 1 / (k + 1) + 1 / (k + 3)
exp_B = 1 / (k + 2) + 1 / (k + 2)
ok("A 与 C 同分并列第一（对称名次）", abs(fused[0]["rrf_score"] - fused[1]["rrf_score"]) < TOL)
ok(f"B 名次稳定（两路都第 2）分数 = 2/(k+2) ≈ {exp_B:.6f}",
   abs([h for h in fused if h["doc_id"] == 2][0]["rrf_score"] - exp_B) < TOL,
   f"实际 {[h for h in fused if h['doc_id'] == 2][0]['rrf_score']}")
ok(f"A 分数符合公式 1/(k+1)+1/(k+3) ≈ {exp_A:.6f}",
   abs([h for h in fused if h["doc_id"] == 1][0]["rrf_score"] - exp_A) < TOL,
   f"实际 {[h for h in fused if h['doc_id'] == 1][0]['rrf_score']}")
check("按 RRF 分数降序（同分时顺序不强制）", fused[-1]["doc_id"], 2)
check("两路都命中的标记 hybrid", [h for h in fused if h["doc_id"] == 2][0]["source"], "hybrid")
check("仅向量路命中标记 vector", [h for h in fused if h["doc_id"] == 3][0]["source"], "hybrid")
ok("融合结果按 rrf_score 递减", all(fused[i]["rrf_score"] >= fused[i + 1]["rrf_score"]
                                 for i in range(len(fused) - 1)))

k_small = vs.rrf_fuse([(1.0, [A, B, C2]), (1.0, [C, B2, A2])], k=1)
r1 = {h["doc_id"]: h["rrf_score"] for h in k_small}
ok("k 越小头部名次权重越大（k=1 时 A(1,3) > B(2,2)）", r1[1] > r1[2], f"A={r1[1]} B={r1[2]}")

print("\n== 4. hybrid_search 编排：融合 + 去重 + TopK ==")
# 同一文档 3 个分块：应只保留 2 段
v_hits = [{"key": f"k{i}", "doc_id": 1 if i < 3 else 2, "chunk_index": i, "text": f"v{i}",
           "distance": 0.1, "score": 0.9, "source": "vector"} for i in range(4)]
kw_hits = [{"key": "k9", "doc_id": 3, "chunk_index": 0, "text": "kw", "source": "keyword"}]

with patch.object(vs, "vector_candidates", return_value=v_hits), \
        patch.object(vs, "keyword_search", return_value=kw_hits):
    out = vs.hybrid_search("售后政策", [0.0] * 8, top_k=3, min_score=0.7)
ok("最终条数不超过 top_k", len(out) <= 3, f"实际 {len(out)}")
ok("同文档最多 2 段", sum(1 for h in out if h["doc_id"] == 1) <= 2)
ok("关键词路命中被融合进来", any(h["doc_id"] == 3 for h in out))
ok("每条都有 score 字段（关键词路为 None）",
   all("score" in h for h in out), [h.get("score") for h in out])
ok("关键词路来源标注保留", all(h["source"] in ("vector", "keyword", "hybrid") for h in out))

with patch.object(vs, "vector_candidates", return_value=[]), \
        patch.object(vs, "keyword_search", return_value=kw_hits):
    out2 = vs.hybrid_search("售后政策", [0.0] * 8, top_k=3, min_score=0.7)
check("向量路空时退回关键词路结果", [h["doc_id"] for h in out2], [3])

with patch.object(vs, "vector_candidates", return_value=v_hits), \
        patch.object(vs, "keyword_search", return_value=[]):
    out3 = vs.hybrid_search("售后政策", [0.0] * 8, top_k=3, min_score=0.7)
ok("关键词路空时保持纯向量结果不变", all(h["source"] == "vector" for h in out3))

with patch.object(vs._settings, "rag_hybrid_enabled", False), \
        patch.object(vs, "keyword_search", side_effect=AssertionError("关闭混合后不应调关键词路")), \
        patch.object(vs, "vector_candidates", return_value=v_hits):
    out4 = vs.hybrid_search("售后政策", [0.0] * 8, top_k=3, min_score=0.7)
ok("rag_hybrid_enabled=False 时退化为纯向量检索（且不触发关键词路）",
   all(h["source"] == "vector" for h in out4))

print("\n== 5. 向量路候选层：阈值过滤与候选倍数 ==")
knn_returns = [
    [b"knowledge:vector:1:0", [b"doc_id", b"1", b"chunk_index", b"0", b"start_pos", b"0",
                               b"text", b"t1", b"distance", b"0.10"]],   # 相似度 0.90 通过
    [b"knowledge:vector:2:0", [b"doc_id", b"2", b"chunk_index", b"0", b"start_pos", b"0",
                               b"text", b"t2", b"distance", b"0.45"]],   # 相似度 0.55 被阈值挡掉
]


def fake_knn(*args, **kwargs):
    captured["knn"] = args
    res = [len(knn_returns)]
    for key, fields in knn_returns:
        res.extend([key, fields])
    return res


with patch.object(vs.sync_redis, "execute_command", side_effect=fake_knn):
    cands = vs.vector_candidates([0.0] * 8, limit=3, min_score=0.7)
knn_cmd = [a.decode() if isinstance(a, bytes) else str(a) for a in captured["knn"]]
check("阈值过滤：低于 min_score 的被剔除", [h["doc_id"] for h in cands], [1])
check("候选层会超取（limit*4）", any("KNN 12" in c for c in knn_cmd), True)
check("相似度换算正确（1 - 距离）", cands[0]["score"], 0.9)
check("标记向量来源", cands[0]["source"], "vector")

print("\n== 6. 展示标签 ==")
check("向量命中展示相似度", hit_label({"source": "vector", "score": 0.83}), "相似度 0.83")
check("关键词命中不伪装相似度", hit_label({"source": "keyword", "score": None}), "关键词命中")
check("混合命中标注两者", hit_label({"source": "hybrid", "score": 0.77}), "混合命中，相似度 0.77")
check("缺 source 时按向量处理", hit_label({"score": 0.5}), "相似度 0.5")

print("\n" + "=" * 50)
print(f"结果：PASS={PASS}  FAIL={FAIL}")
print("=" * 50)
sys.exit(0 if FAIL == 0 else 1)
