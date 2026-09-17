"""知识库管理接口：上传（pdf/docx/txt/md，≤20MB）→ 异步向量化；列表分页；删除联动清理"""
import logging
from pathlib import Path

from fastapi import APIRouter, Depends, File, Query, UploadFile
from sqlalchemy.ext.asyncio import AsyncSession

from app.agent.rag.document_processor import is_allowed
from app.common.exceptions import ParamException
from app.common.identity import required_admin
from app.common.result import Result
from app.common.utils.snowflake import snowflake
from app.config import get_settings
from app.database.mysql import get_db
from app.services import knowledge_service

logger = logging.getLogger("app.api")

router = APIRouter(prefix="/api/agent/knowledge", tags=["知识库管理"])

_settings = get_settings()

# 上传时单次读取的块大小（1MB）
UPLOAD_CHUNK_SIZE = 1024 * 1024


def _remove_quietly(path: Path) -> None:
    """清理写了一半的文件；失败只忽略（避免掩盖原始错误）"""
    try:
        if path.exists():
            path.unlink()
    except Exception:
        logger.warning("清理临时文件 %s 失败", path)


@router.post("/upload")
async def upload_document(file: UploadFile = File(...),
                         _admin: int = Depends(required_admin),
                         db: AsyncSession = Depends(get_db)):
    """上传知识库文档（pdf/docx/txt/md，≤20MB）"""
    filename = file.filename or "unnamed"
    if not is_allowed(filename):
        raise ParamException("仅支持 pdf / docx / txt / md 格式")

    max_bytes = _settings.upload_max_mb * 1024 * 1024
    doc_type = filename.rsplit(".", 1)[-1].lower()
    # 保存本地：雪花文件名防重（不做用户文件名拼接，天然避免路径穿越）
    save_path = _settings.files_dir / f"{snowflake.next_id()}.{doc_type}"

    # 边读边写边校验：此前是 `content = await file.read()` 先整个读进内存再判断大小，
    # 20MB 上限形同虚设（超大文件会先把内存撑爆）。现在分块流式落盘，
    # 一旦超过上限立即中止并清理半成品文件，内存占用与文件大小无关。
    size = 0
    try:
        with open(save_path, "wb") as out:
            while True:
                chunk = await file.read(UPLOAD_CHUNK_SIZE)
                if not chunk:
                    break
                size += len(chunk)
                if size > max_bytes:
                    raise ParamException(f"文件大小超过上限（{_settings.upload_max_mb}MB）")
                out.write(chunk)
    except ParamException:
        _remove_quietly(save_path)
        raise
    except Exception as e:
        _remove_quietly(save_path)
        logger.warning("保存上传文件 %s 失败: %s", filename, e)
        raise ParamException("文件保存失败")

    if size == 0:
        _remove_quietly(save_path)
        raise ParamException("文件内容为空")

    doc_id = await knowledge_service.create_document(db, filename, doc_type, save_path)
    return Result.ok({"doc_id": doc_id})


@router.get("/list")
async def list_documents(page_num: int = Query(1), page_size: int = Query(10),
                         _admin: int = Depends(required_admin),
                         db: AsyncSession = Depends(get_db)):
    """获取文档分页列表"""
    return Result.ok(await knowledge_service.list_documents(db, page_num, page_size))


@router.delete("/delete/{doc_id}")
async def delete_document(doc_id: int,
                          _admin: int = Depends(required_admin),
                          db: AsyncSession = Depends(get_db)):
    """删除文档（DB 行 + 本地文件 + 向量键）"""
    await knowledge_service.delete_document(db, doc_id)
    return Result.ok()
