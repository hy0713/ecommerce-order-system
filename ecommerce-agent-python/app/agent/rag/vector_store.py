"""向量存储与检索：裸 RediSearch 命令封装（redis-py 5 execute_command）

索引 idx:agent_knowledge（HASH，前缀 knowledge:vector）：
  doc_id TAG / chunk_index NUMERIC / start_pos NUMERIC / text TEXT /
  tokens TEXT / embedding VECTOR FLAT 6 TYPE FLOAT32 DIM <EMBED_DIM> DISTANCE_METRIC COSINE

检索：**混合检索** —— BM25 关键词路（@tokens 字段）+ 向量 KNN 路 → RRF 融合
     → 向量路阈值过滤 → 按 doc_id 去重 → 取 TopK

为什么自己算 tokens 而不依赖 RediSearch 的分词器：
  RediSearch 默认分词按空白/标点切分，连续中文会被当成**一个超长 token**，
  于是「售后政策」这类查询在 BM25 上几乎不可能命中。这里在写入与查询两侧
  用同一套「ASCII 词 + 中文二元组」切分（见 text_to_tokens），
  不依赖服务端 LANGUAGE 支持，行为可预期、可单测。
"""
import logging
import re
from typing import Optional

import numpy as np

from app.config import get_settings
from app.database.redis import sync_redis

logger = logging.getLogger("app.vector")

_settings = get_settings()

INDEX_NAME = "idx:agent_knowledge"
KEY_PREFIX = "knowledge:vector"

VECTOR_FIELDS = {"type": "FLOAT32", "dim": _settings.embed_dim, "distance_metric": "COSINE"}

# 返回给上层的字段（向量路额外带 distance）
_RETURN_FIELDS = ["doc_id", "chunk_index", "start_pos", "text"]

_ASCII_WORD = re.compile(r"[a-z0-9]+")
_CJK = re.compile(r"[\u4e00-\u9fff]+")


def text_to_tokens(text: str) -> list[str]:
    """切分检索词：ASCII 单词 + 中文二元组（bigram）。

    - `'iPhone 15 Pro'` → ['iphone', 'pro']（`15` 被下面的规则剔除）
    - `'售后政策'`      → ['售后', '后政', '政策']

    中文用 bigram 而非单字：单字噪声太大（"的""是"命中一堆），
    二元组在小规模知识库里召回率与准确率的平衡最好，也不需要词典。

    **1~2 位纯数字（`7`、`15`）刻意剔除**：这类 token 在中文文本里出现频率极高、
    区分度极低（"7 天"、"24 小时"、"15 天"），语料很小时 IDF 也救不回来，
    只会让 BM25 误召回——实测「iPhone 15 的库存还有多少」曾被政策文档里的
    「15 天」命中。保留词语与 3 位以上数字（订单号、政策编号如 2026/0916）。
    """
    if not text:
        return []
    lowered = str(text).lower()
    tokens: list[str] = []
    for w in _ASCII_WORD.findall(lowered):
        if w.isdigit() and len(w) < 3:
            continue
        tokens.append(w)
    for seg in _CJK.findall(lowered):
        if len(seg) == 1:
            tokens.append(seg)
        else:
            tokens.extend(seg[i:i + 2] for i in range(len(seg) - 1))
    # 去重但保持顺序
    seen = set()
    out = []
    for t in tokens:
        if t not in seen:
            seen.add(t)
            out.append(t)
    return out


def vector_key(doc_id: int, chunk_index: int) -> str:
    return f"{KEY_PREFIX}:{doc_id}:{chunk_index}"


def ensure_index() -> bool:
    """幂等创建混合检索索引；失败返回 False（由调用方决定降级策略）

    索引结构升级（新增 tokens 字段）时：老索引缺字段会让 BM25 路整条失效，
    因此检测到字段缺失就 DROP + CREATE（不带 DD，哈希数据保留，重建索引时自动重扫），
    再对已有哈希补齐 tokens 字段。
    """
    try:
        existing = sync_redis.execute_command("FT._LIST")
        has_index = INDEX_NAME.encode() in existing or INDEX_NAME in existing
        if has_index and _index_has_tokens_field():
            return True
        if has_index:
            logger.info("索引 %s 缺少 tokens 字段，重建以启用混合检索", INDEX_NAME)
            sync_redis.execute_command("FT.DROPINDEX", INDEX_NAME)
        sync_redis.execute_command(
            "FT.CREATE", INDEX_NAME, "ON", "HASH", "PREFIX", "1", KEY_PREFIX, "SCHEMA",
            "doc_id", "TAG",
            "chunk_index", "NUMERIC",
            "start_pos", "NUMERIC",
            "text", "TEXT",
            "tokens", "TEXT",
            "embedding", "VECTOR", "FLAT", "6",
            "TYPE", VECTOR_FIELDS["type"],
            "DIM", VECTOR_FIELDS["dim"],
            "DISTANCE_METRIC", VECTOR_FIELDS["distance_metric"],
        )
        logger.info("混合检索索引 %s 已创建", INDEX_NAME)
        backfilled = _backfill_tokens()
        if backfilled:
            logger.info("已为 %s 条历史分块补齐 tokens 字段", backfilled)
        return True
    except Exception as e:
        logger.warning("索引创建失败: %s", e)
        return False


def _index_has_tokens_field() -> bool:
    """FT.INFO 里是否声明了 tokens 字段（用于识别需要重建的老索引）"""
    try:
        info = sync_redis.execute_command("FT.INFO", INDEX_NAME)
    except Exception:
        return False
    for item in info or []:
        name = item.decode() if isinstance(item, bytes) else item
        if name == "tokens":
            return True
    return False


def _backfill_tokens() -> int:
    """为索引外的历史哈希补齐 tokens（索引重建不会回填哈希字段）"""
    filled = 0
    try:
        cursor = 0
        while True:
            cursor, keys = sync_redis.scan(cursor=cursor, match=f"{KEY_PREFIX}:*", count=500)
            for key in keys:
                text = sync_redis.hget(key, "text")
                if text is None:
                    continue
                if sync_redis.hexists(key, "tokens"):
                    continue
                if isinstance(text, bytes):
                    text = text.decode("utf-8", errors="replace")
                sync_redis.hset(key, "tokens", " ".join(text_to_tokens(text)))
                filled += 1
            if cursor == 0:
                break
    except Exception as e:
        logger.warning("补齐 tokens 失败: %s", e)
    return filled


def add_chunks(doc_id: int, chunks: list[dict], vectors: list[list[float]]) -> int:
    """写入分块哈希（每块一个 key），返回写入条数"""
    pipe = sync_redis.pipeline(transaction=False)
    for i, (chunk, vec) in enumerate(zip(chunks, vectors)):
        pipe.hset(
            vector_key(doc_id, chunk["chunk_index"]),
            mapping={
                "doc_id": str(doc_id),
                "chunk_index": chunk["chunk_index"],
                "start_pos": chunk["start_pos"],
                "text": chunk["text"],
                # BM25 路专用：写入侧与查询侧用同一套切分，保证能对上
                "tokens": " ".join(text_to_tokens(chunk["text"])),
                "embedding": np.asarray(vec, dtype=np.float32).tobytes(),
            },
        )
    pipe.execute()
    return len(chunks)


def delete_by_doc_id(doc_id: int) -> int:
    """按 doc_id 删除全部向量哈希。

    <p>历史实现写的是 ``FT.SEARCH ... DEL``，但 **FT.SEARCH 并没有 DEL 参数**
    （官方可选参数只有 NOCONTENT / RETURN / LIMIT / SORTBY / PARAMS / DIALECT 等，
    没有任何删除机制），命令必然报语法错误并被 except 吞掉并返回 0 ——
    表现是「删除接口返回 200 成功，但向量一条没删」，已删文档仍会被 RAG 召回注入 prompt。

    这里改为按 key 前缀扫描后显式 DEL：不依赖索引是否存在，且能顺带清掉索引外的残留。
    """
    pattern = f"{KEY_PREFIX}:{doc_id}:*"
    deleted = 0
    try:
        cursor = 0
        batch: list = []
        while True:
            cursor, keys = sync_redis.scan(cursor=cursor, match=pattern, count=500)
            batch.extend(keys)
            # 分批删除，避免单条命令携带过多 key
            while len(batch) >= 200:
                chunk, batch = batch[:200], batch[200:]
                deleted += sync_redis.delete(*chunk)
            if cursor == 0:
                break
        if batch:
            deleted += sync_redis.delete(*batch)
        logger.info("删除文档 %s 向量 %s 条", doc_id, deleted)
    except Exception as e:
        logger.warning("删除文档 %s 的向量失败: %s", doc_id, e)
    return int(deleted)


def vector_candidates(query_vector: list[float], limit: int, min_score: float) -> list[dict]:
    """向量路**候选层**：KNN 取 limit*4 → 余弦距离阈值过滤 → 返回前 limit 条（不去重、不截 TopK）

    与收尾层分开的原因：融合前需要比最终答案更大的候选池，
    否则向量路只交出 3 条，RRF 就没有可融合的余地了。
    """
    max_distance = 1 - min_score
    fetch = max(limit * 4, limit)
    try:
        blob = np.asarray(query_vector, dtype=np.float32).tobytes()
        res = sync_redis.execute_command(
            "FT.SEARCH", INDEX_NAME,
            f"*=>[KNN {fetch} @embedding $BLOB AS distance]",
            "PARAMS", "2", "BLOB", blob,
            "SORTBY", "distance", "ASC",
            "LIMIT", "0", str(fetch),
            "RETURN", "5", "doc_id", "chunk_index", "start_pos", "text", "distance",
            # DIALECT 2 才支持 =>[KNN] 向量相似度查询（dialect 1 报 Syntax error）
            "DIALECT", "2",
        )
    except Exception as e:
        logger.warning("向量检索失败: %s", e)
        return []

    hits = _parse_results(res)
    hits = [h for h in hits if h["distance"] <= max_distance]
    for h in hits:
        h["source"] = "vector"
    return hits[:limit]


def search(query_vector: list[float], top_k: int = None, min_score: float = None) -> list[dict]:
    """纯向量检索（保留原签名，供不启用混合检索时回退）

    返回：[{"doc_id", "chunk_index", "start_pos", "text", "distance", "score", "source"}]
    相似度 = 1 - 余弦距离（COSINE 距离 ∈ [0,2]）
    """
    top_k = top_k or _settings.rag_top_k
    min_score = min_score if min_score is not None else _settings.rag_min_score
    return _finalize(vector_candidates(query_vector, top_k, min_score), top_k)



def keyword_search(query_text: str, limit: int = None) -> list[dict]:
    """BM25 关键词检索（@tokens 字段），按相关性降序返回。

    查询词由 text_to_tokens 生成（只含字母/数字/汉字），因此**不需要再做
    RediSearch 特殊字符转义** —— 这一点是刻意的：手写转义漏一个字符就是一条
    线上语法错误，把查询词限制在安全字符集里更稳。
    """
    limit = limit or _settings.keyword_candidates
    tokens = text_to_tokens(query_text)
    if not tokens:
        return []
    # 用 | 连接成 OR：任一词命中即召回，排名交还给 BM25 打分
    query = "@tokens:(" + "|".join(tokens) + ")"
    try:
        res = sync_redis.execute_command(
            "FT.SEARCH", INDEX_NAME, query,
            "LIMIT", "0", str(limit),
            "RETURN", "4", *_RETURN_FIELDS,
        )
    except Exception as e:
        logger.warning("关键词检索失败: %s", e)
        return []
    hits = _parse_results(res)
    for h in hits:
        h["source"] = "keyword"
    return hits


def rrf_fuse(ranked_lists: list[tuple[float, list[dict]]], k: int = None) -> list[dict]:
    """RRF（Reciprocal Rank Fusion）融合多路召回。

    score(d) = Σ_lists weight / (k + rank(d))，rank 从 1 开始，k 默认 60。

    为什么用排名而不是分数：余弦相似度（0~1，越接近 1 越好）与 BM25 分数
    （无上界、随库规模变化）**量纲不可比**，归一化也依赖分布假设；
    只用名次融合可以完全绕开这个问题，也是 RRF 原始论文的设计意图。
    """
    k = k or _settings.rrf_k
    merged: dict[str, dict] = {}
    fused: dict[str, float] = {}
    for weight, hits in ranked_lists:
        for rank, h in enumerate(hits, start=1):
            key = h.get("key") or f"{h.get('doc_id')}:{h.get('chunk_index')}"
            entry = merged.get(key)
            if entry is None:
                entry = dict(h)
                entry["sources"] = set()
                merged[key] = entry
                fused[key] = 0.0
            entry["sources"].add(h.get("source", "vector"))
            fused[key] += weight / (k + rank)
    out = []
    for key, entry in merged.items():
        entry["rrf_score"] = round(fused[key], 6)
        if len(entry["sources"]) > 1:
            entry["source"] = "hybrid"
        else:
            entry["source"] = next(iter(entry["sources"]))
        out.append(entry)
    out.sort(key=lambda x: x["rrf_score"], reverse=True)
    return out


def hybrid_search(query_text: str, query_vector: list[float],
                  top_k: int = None, min_score: float = None) -> list[dict]:
    """混合检索：BM25 关键词路 + 向量路 → RRF 融合 → 去重取 TopK

    - 向量路仍保留相似度阈值（防止语义相近但无关的片段进来），并按 top_k*2 取候选；
    - 关键词路不设分数阈值（BM25 分数无上界，阈值没法定），靠 RRF 名次自然调节；
    - 关掉 rag_hybrid_enabled 即退回纯向量检索，便于对比排障。
    """
    top_k = top_k or _settings.rag_top_k
    min_score = min_score if min_score is not None else _settings.rag_min_score

    vector_hits = vector_candidates(query_vector, max(top_k * 2, top_k), min_score)
    if not _settings.rag_hybrid_enabled:
        return _finalize(vector_hits, top_k)

    keyword_hits = keyword_search(query_text, _settings.keyword_candidates)
    if not keyword_hits:
        return _finalize(vector_hits, top_k)
    if not vector_hits:
        return _finalize(keyword_hits, top_k)

    fused = rrf_fuse([(1.0, vector_hits), (1.0, keyword_hits)], _settings.rrf_k)
    return _finalize(fused, top_k)


def _finalize(hits: list[dict], top_k: int) -> list[dict]:
    """统一收尾：补 score 字段（关键词路没有余弦相似度，置 None 由展示层区分）
    → 按 doc_id 去重（每文档最多 2 段，保持原有顺序）→ 取 TopK"""
    for h in hits:
        h.setdefault("score", None)
        h.pop("sources", None)
    seen: dict[str, int] = {}
    deduped = []
    for h in hits:
        doc = h["doc_id"]
        if doc not in seen:
            seen[doc] = 1
            deduped.append(h)
        elif seen[doc] < 2:
            seen[doc] += 1
            deduped.append(h)
    return deduped[:top_k]


def _parse_results(res) -> list[dict]:
    """解析 FT.SEARCH 返回：count, [key, [field, value, ...], ...]

    只有向量路会 RETURN distance，因此 distance/score 仅在该字段存在时写入，
    关键词路不应伪造出「相似度 0」这种误导性数字。
    """
    hits = []
    if not res or len(res) < 2:
        return hits
    try:
        for key, fields in zip(res[1::2], res[2::2]):
            d = {"key": key.decode() if isinstance(key, bytes) else key}
            for i in range(0, len(fields) - 1, 2):
                name = fields[i].decode() if isinstance(fields[i], bytes) else fields[i]
                val = fields[i + 1]
                if isinstance(val, bytes):
                    val = val.decode("utf-8", errors="replace")
                d[name] = val
            d["doc_id"] = int(d.get("doc_id", 0))
            d["chunk_index"] = int(float(d.get("chunk_index", 0)))
            d["start_pos"] = int(float(d.get("start_pos", 0)))
            if "distance" in d:
                d["distance"] = float(d["distance"])
                d["score"] = round(1 - d["distance"], 4)
            hits.append(d)
    except Exception as e:
        logger.warning("检索结果解析失败: %s", e)
    return hits


def search_by_text(question_embedding: list[float], top_k: int = 3, min_score: float = 0.5,
                   query_text: str = None) -> list[dict]:
    """售后政策工具用的宽松检索（独立阈值）；给了 query_text 就走混合检索"""
    if query_text:
        return hybrid_search(query_text, question_embedding, top_k=top_k, min_score=min_score)
    return search(question_embedding, top_k=top_k, min_score=min_score)

