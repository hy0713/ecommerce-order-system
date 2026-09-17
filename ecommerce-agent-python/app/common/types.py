"""通用类型定义。

SnowflakeId：雪花 ID 在 JSON 中序列化为**字符串**。

为什么必须这么做：雪花算法产出 19 位数字，超出 JavaScript 的
`Number.MAX_SAFE_INTEGER`（2^53-1 ≈ 9007199254740991，16 位）。
前端 `JSON.parse('{"id":2098249056107450369}')` 会得到 `2098249056107450400`，
把该值回传后端就查不到数据（「会话不存在」「订单不存在」等）。

只影响序列化方向；校验（入参）仍接受 int 或数字字符串，Pydantic 会精确转换。
"""
from typing import Annotated

from pydantic import PlainSerializer

SnowflakeId = Annotated[int, PlainSerializer(lambda v: str(v), return_type=str)]
