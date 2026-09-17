"""文档解析与分块：pdf / docx / txt / md → 清洗 → RecursiveCharacterTextSplitter 分块"""
import logging
import re
from pathlib import Path

from langchain.text_splitter import RecursiveCharacterTextSplitter

from app.config import get_settings

logger = logging.getLogger("app.doc")

_settings = get_settings()

ALLOWED_EXTENSIONS = {"pdf", "docx", "txt", "md"}


def is_allowed(filename: str) -> bool:
    return filename.rsplit(".", 1)[-1].lower() in ALLOWED_EXTENSIONS if "." in filename else False


def extract_text(path: Path, doc_type: str) -> str:
    """按类型解析为纯文本"""
    if doc_type == "pdf":
        from pypdf import PdfReader
        reader = PdfReader(str(path))
        return "\n".join(page.extract_text() or "" for page in reader.pages)
    if doc_type == "docx":
        import docx
        d = docx.Document(str(path))
        return "\n".join(p.text for p in d.paragraphs)
    # txt / md：utf-8 读取，坏字节替换
    return path.read_text(encoding="utf-8", errors="replace")


def clean_text(text: str) -> str:
    """清洗：去控制字符、多余空行、首尾空白"""
    text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", text)      # 控制字符
    text = re.sub(r"[ \t]+\n", "\n", text)                         # 行尾空白
    text = re.sub(r"\n{3,}", "\n\n", text)                         # 连续空行压缩
    return text.strip()


def split_chunks(text: str) -> list[dict]:
    """递归字符分块：500 字符 / 100 重叠，附加元数据（块序号、真实起始位置）

    <p>必须用 {@code create_documents} 而不是 {@code split_text}：只有前者会走
    {@code _merge_splits} 并写入 {@code add_start_index} 生成的 {@code start_index} 元数据。
    历史实现用 {@code split_text}（只返回字符串列表，拿不到位置），于是退化成
    {@code i * (chunk_size - chunk_overlap)} 估算 —— 只要某一块不足 chunk_size
    （递归切分下极为常见），其后所有块的 start_pos 就整体错位。
    """
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=_settings.chunk_size,
        chunk_overlap=_settings.chunk_overlap,
        add_start_index=True,       # 元数据带 start_index（起始字符位置）
    )
    docs = splitter.create_documents([text])
    chunks = []
    for i, d in enumerate(docs):
        start = d.metadata.get("start_index")
        if start is None:
            # 兜底：元数据缺失时按内容定位，仍优于按固定步长估算
            start = max(text.find(d.page_content), 0)
        chunks.append({
            "chunk_index": i,
            "start_pos": start,
            "text": d.page_content,
        })
    return chunks


def process_document_file(path: Path, title: str) -> list[dict]:
    """完整解析流水线：读取 → 清洗 → 分块"""
    doc_type = path.suffix.lstrip(".").lower()
    raw = extract_text(path, doc_type)
    cleaned = clean_text(raw)
    if not cleaned:
        raise ValueError("文档内容为空或无法解析")
    chunks = split_chunks(cleaned)
    if not chunks:
        raise ValueError("文档分块结果为空")
    return chunks
