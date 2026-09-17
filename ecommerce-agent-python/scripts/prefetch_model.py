"""一次性下载 embedding 模型（走 HF 镜像），校验输出维度，之后可离线使用

用法：python scripts/prefetch_model.py
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.config import get_settings  # noqa: E402   （导入即注入 HF_ENDPOINT 镜像变量）

_settings = get_settings()


def main():
    os.environ.setdefault("HF_ENDPOINT", _settings.hf_endpoint)
    print(f"下载 embedding 模型: {_settings.embed_model}")
    print(f"缓存目录: {_settings.model_dir}（HF_ENDPOINT={os.environ['HF_ENDPOINT']}）")

    from fastembed import TextEmbedding

    _settings.model_dir.mkdir(parents=True, exist_ok=True)
    model = TextEmbedding(model_name=_settings.embed_model, cache_dir=str(_settings.model_dir), threads=4)
    vec = list(next(model.embed(["模型维度校验"])))
    dim = len(vec)
    print(f"模型加载成功，输出维度: {dim}")

    if dim != _settings.embed_dim:
        print(f"错误: 实际维度 {dim} != 配置 EMBED_DIM={_settings.embed_dim}，请同步修改 .env")
        sys.exit(1)
    print("✓ 模型预热完成，可离线使用（后续启动无需联网）")


if __name__ == "__main__":
    main()
