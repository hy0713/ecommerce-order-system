"""自定义异常 + FastAPI 全局异常处理器"""
import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.common.result import Result

logger = logging.getLogger("app.exception")


class BusinessException(Exception):
    """业务异常，对外返回其 message"""

    def __init__(self, message: str, code: int = 500):
        self.message = message
        self.code = code
        super().__init__(message)


class ParamException(BusinessException):
    """参数错误"""

    def __init__(self, message: str = "参数错误"):
        super().__init__(message, code=400)


class UnauthorizedException(BusinessException):
    """未登录"""

    def __init__(self, message: str = "未登录或登录已过期"):
        super().__init__(message, code=401)


class ForbiddenException(BusinessException):
    """已登录但权限不足（如非管理员操作知识库）"""

    def __init__(self, message: str = "无权限执行该操作"):
        super().__init__(message, code=403)


def register_exception_handlers(app: FastAPI) -> None:
    """注册全局异常处理器：HTTP 恒为 200，业务码在 Result 体内；系统异常信息脱敏"""

    @app.exception_handler(BusinessException)
    async def handle_business(_: Request, exc: BusinessException):
        return JSONResponse(status_code=200, content=Result.fail(exc.message, exc.code).model_dump())

    @app.exception_handler(RequestValidationError)
    async def handle_validation(_: Request, exc: RequestValidationError):
        # 参数校验失败：拼接字段错误信息（脱敏，不暴露内部结构）
        msgs = []
        for err in exc.errors():
            loc = ".".join(str(x) for x in err.get("loc", []) if x != "body")
            msg = err.get("msg", "参数错误")
            msgs.append(f"{loc}: {msg}")
        return JSONResponse(status_code=200, content=Result.fail("；".join(msgs[:5]) or "参数错误", 400).model_dump())

    @app.exception_handler(Exception)
    async def handle_system(_: Request, exc: Exception):
        logger.exception("未捕获系统异常: %s", exc)
        return JSONResponse(status_code=200, content=Result.fail("系统繁忙，请稍后重试", 500).model_dump())
