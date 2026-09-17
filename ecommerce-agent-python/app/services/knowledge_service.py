"""知识库服务：文档元数据、分页、删除（DB 行 + 文件 + 向量三处联动）、MQ 触发向量化"""
import asyncio
import json
import logging
from datetime import datetime
from pathlib import Path

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.agent.rag.document_processor import process_document_file
from app.agent.rag.embedding_service import embedding_service
from app.agent.rag import vector_store
from app.common.enums import DocStatus
from app.common.exceptions import BusinessException
from app.common.utils.snowflake import snowflake
from app.config import get_settings
from app.database.models import KnowledgeDocument
from app.database.mq import DOC_EXCHANGE, DOC_ROUTING_KEY, mq_manager
from app.schemas.knowledge import DocVO, PageResult

logger = logging.getLogger("app.knowledge")

_settings = get_settings()


def _to_vo(row: KnowledgeDocument) -> DocVO:
    vo = DocVO.model_validate(row, from_attributes=True)
    vo.status_desc = DocStatus.TEXT.get(row.status, "")
    return vo


async def create_document(db: AsyncSession, title: str, doc_type: str, file_path: Path) -> int:
    """落库（待处理）→ 发 MQ 触发向量化；MQ 不可用降级内联处理"""
    doc = KnowledgeDocument(title=title[:128], doc_type=doc_type, file_url=str(file_path), status=DocStatus.PENDING)
    db.add(doc)
    await db.commit()
    await db.refresh(doc)

    payload = {"doc_id": doc.id, "title": doc.title, "file_url": str(file_path), "doc_type": doc.doc_type}
    if mq_manager.publish(DOC_EXCHANGE, DOC_ROUTING_KEY, payload):
        return doc.id
    logger.warning("MQ 不可用，文档 %s 降级内联向量化", doc.id)
    await mq_doc_handler(payload)
    return doc.id


async def list_documents(db: AsyncSession, page_num: int = 1, page_size: int = 10) -> PageResult:
    page_num, page_size = max(page_num, 1), min(max(page_size, 1), 100)
    total = (await db.execute(select(func.count()).select_from(KnowledgeDocument))).scalar() or 0
    rows = (await db.execute(
        select(KnowledgeDocument).order_by(KnowledgeDocument.create_time.desc())
        .offset((page_num - 1) * page_size).limit(page_size))).scalars().all()
    return PageResult(records=[_to_vo(r) for r in rows], total=total, page_num=page_num, page_size=page_size)


async def delete_document(db: AsyncSession, doc_id: int) -> None:
    doc = await db.get(KnowledgeDocument, doc_id)
    if doc is None:
        raise BusinessException("文档不存在", code=404)
    # 1) 清向量 2) 删本地文件 3) 删 DB 行
    # 同步 Redis 与磁盘 IO 必须放到线程池：本函数是 async，直接调用会阻塞整个事件循环
    # （包括 /health 与其它请求）。agent_core / aftersale_tools 已统一用 to_thread，此处补上。
    await asyncio.to_thread(vector_store.delete_by_doc_id, doc_id)
    await asyncio.to_thread(_remove_file_quietly, doc.file_url)
    await db.delete(doc)
    await db.commit()


def _remove_file_quietly(file_url: str) -> None:
    """删除本地文件；失败只告警，不影响 DB 记录删除"""
    try:
        p = Path(file_url)
        if p.exists():
            p.unlink()
    except Exception as e:
        logger.warning("删除文件 %s 失败: %s", file_url, e)


# ---------- MQ 消费者：文档向量化 ----------
async def mq_doc_handler(payload: dict) -> None:
    """消费者：解析 → 分块 → embedding → 写向量库 → 更新状态"""
    from app.database.mysql import AsyncSessionLocal

    doc_id = payload["doc_id"]
    file_url = payload["file_url"]
    doc_type = payload["doc_type"]
    fail_reason = None

    async with AsyncSessionLocal() as db:
        doc = await db.get(KnowledgeDocument, doc_id)
        if doc is None:
            return
        doc.status = DocStatus.PROCESSING
        await db.commit()
        try:
            # 解析 / 分块 / 本地 embedding / 写 Redis 全是同步阻塞操作，
            # 必须丢到线程池，否则 20MB 文档会把整个事件循环卡住数秒
            chunks = await asyncio.to_thread(process_document_file, Path(file_url), payload.get("title", ""))
            vectors = await asyncio.to_thread(embedding_service.embed_documents, [c["text"] for c in chunks])
            await asyncio.to_thread(vector_store.add_chunks, doc_id, chunks, vectors)
            doc.chunk_count = len(chunks)
            doc.status = DocStatus.READY
            doc.fail_reason = None
        except Exception as e:
            logger.exception("文档 %s 向量化失败: %s", doc_id, e)
            doc.status = DocStatus.FAILED
            doc.fail_reason = str(e)[:255]
            fail_reason = str(e)
        doc.update_time = datetime.now()
        await db.commit()
        if fail_reason:
            raise RuntimeError(fail_reason)   # 触发 MQ nack(requeue=False)
