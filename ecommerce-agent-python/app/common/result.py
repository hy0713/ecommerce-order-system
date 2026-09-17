"""统一返回结果封装：与电商 Java 版 Result 字段语义对齐 {code, message, data}"""
from typing import Any, Optional
from pydantic import BaseModel


class Result(BaseModel):
    code: int = 200
    message: str = "操作成功"
    data: Optional[Any] = None

    @staticmethod
    def ok(data: Any = None, message: str = "操作成功") -> "Result":
        return Result(code=200, message=message, data=data)

    @staticmethod
    def fail(message: str = "操作失败", code: int = 500, data: Any = None) -> "Result":
        return Result(code=code, message=message, data=data)

    @staticmethod
    def unauthorized(message: str = "未登录或登录已过期") -> "Result":
        return Result(code=401, message=message, data=None)
