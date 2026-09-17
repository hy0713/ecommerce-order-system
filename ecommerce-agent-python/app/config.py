"""全局配置：从 .env 读取，pydantic-settings 校验"""
import os
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# 项目根目录
BASE_DIR = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=str(BASE_DIR / ".env"), env_file_encoding="utf-8", extra="ignore")

    # 服务
    app_host: str = "0.0.0.0"
    app_port: int = 8000

    # 大模型（OpenAI 兼容协议）
    llm_base_url: str = "https://api.deepseek.com"
    llm_api_key: str = ""
    llm_model: str = "deepseek-chat"
    llm_temperature: float = 0.3
    llm_max_tokens: int = 2048
    llm_timeout: int = 120
    llm_max_retries: int = 2

    # MySQL
    mysql_host: str = "127.0.0.1"
    mysql_port: int = 3306
    mysql_user: str = "root"
    mysql_password: str = "root123456"
    mysql_db: str = "ecommerce"

    # Redis Stack
    redis_host: str = "127.0.0.1"
    redis_port: int = 6380
    redis_db: int = 0
    redis_password: str = ""

    # RabbitMQ
    rabbitmq_host: str = "127.0.0.1"
    rabbitmq_port: int = 5672
    rabbitmq_user: str = "guest"
    rabbitmq_password: str = "guest"

    # 电商系统网关
    ecom_gateway_base: str = "http://localhost:8080"

    # Embedding
    embed_model: str = "BAAI/bge-small-zh-v1.5"
    embed_dim: int = 512
    embed_cache_dir: str = "models"
    hf_endpoint: str = "https://hf-mirror.com"

    # Agent 参数
    ctx_turns: int = 5            # 上下文窗口：最近 N 轮
    max_history_chars: int = 4000
    rag_top_k: int = 3            # 向量检索 TopK
    # 相似度阈值：实测本项目知识库（bge-small-zh + 短文档）相似度集中在 0.44~0.69，
    # 原先 0.7 是**死阈值**——向量路永远命中 0 条，RAG 等于没开（表现为「知识库中没找到」）。
    # 取 0.5 与售后工具阈值对齐：明显无关的问题（<0.5）仍被挡，真命中的能进来。
    rag_min_score: float = 0.5
    rag_aftersale_min_score: float = 0.5
    # ── 混合检索（BM25 关键词路 + 向量路，RRF 融合）──────────────
    rag_hybrid_enabled: bool = True   # 关掉即退回纯向量检索（便于对比/排障）
    rrf_k: int = 60                   # RRF 常数：score = Σ 1/(k + rank)
    keyword_candidates: int = 10      # 关键词路候选数（进 RRF 融合前）
    chunk_size: int = 500         # 分块大小
    chunk_overlap: int = 100      # 分块重叠
    upload_max_mb: int = 20       # 上传上限

    # 派生属性
    @property
    def mysql_url(self) -> str:
        return (f"mysql+aiomysql://{self.mysql_user}:{self.mysql_password}"
                f"@{self.mysql_host}:{self.mysql_port}/{self.mysql_db}?charset=utf8mb4")

    @property
    def files_dir(self) -> Path:
        d = BASE_DIR / "files"
        d.mkdir(parents=True, exist_ok=True)
        return d

    @property
    def model_dir(self) -> Path:
        return BASE_DIR / self.embed_cache_dir

    def safe_llm(self) -> str:
        """脱敏打印：隐藏 API Key"""
        return f"base_url={self.llm_base_url} model={self.llm_model} api_key={'***' if self.llm_api_key else '(empty)'}"


@lru_cache
def get_settings() -> Settings:
    # HF 镜像环境变量需在 import fastembed 前生效，这里提前注入
    s = Settings()
    if s.hf_endpoint:
        os.environ.setdefault("HF_ENDPOINT", s.hf_endpoint)
    return s
