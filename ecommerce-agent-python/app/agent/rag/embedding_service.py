"""向量生成服务：自定义 langchain Embeddings 包装（fastembed 本地模型，离线可用）

- 模型：BAAI/bge-small-zh-v1.5（512 维），默认从 models/ 缓存加载
- query 侧加 bge 官方中文指令前缀，文档侧不加（官方推荐用法）
- 懒加载单例 + threading.Lock，CPU 密集调用方自行放线程池
"""
import logging
import threading
from typing import List

from langchain_core.embeddings import Embeddings

from app.config import get_settings

logger = logging.getLogger("app.embedding")

_settings = get_settings()

# bge 系列官方检索指令前缀（仅 query 使用）
QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章："


class FastEmbedEmbeddings(Embeddings):
    """基于 fastembed 的本地嵌入（langchain-core Embeddings 抽象实现）"""

    def __init__(self):
        self._model = None
        self._lock = threading.Lock()

    def _ensure_model(self):
        if self._model is None:
            with self._lock:
                if self._model is None:
                    from fastembed import TextEmbedding
                    _settings.model_dir.mkdir(parents=True, exist_ok=True)
                    self._model = TextEmbedding(
                        model_name=_settings.embed_model,
                        cache_dir=str(_settings.model_dir),
                        threads=4,
                    )
                    dim = len(next(self._model.embed(["维度校验"])))
                    if dim != _settings.embed_dim:
                        raise RuntimeError(
                            f"模型 {_settings.embed_model} 输出维度 {dim} 与配置 EMBED_DIM={_settings.embed_dim} 不一致")
                    logger.info("embedding 模型加载完成: %s (dim=%s)", _settings.embed_model, dim)
        return self._model

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        """文档批量向量化（不加指令前缀）"""
        if not texts:
            return []
        model = self._ensure_model()
        return [list(map(float, v)) for v in model.embed(texts)]

    def embed_query(self, text: str) -> List[float]:
        """查询向量化（加检索指令前缀）"""
        model = self._ensure_model()
        vec = next(model.embed([f"{QUERY_INSTRUCTION}{text}"]))
        return list(map(float, vec))


# 全局单例
embedding_service = FastEmbedEmbeddings()
