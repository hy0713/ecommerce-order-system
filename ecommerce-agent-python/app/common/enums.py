"""枚举类：与电商系统语义对齐"""


class DocStatus:
    """知识文档处理状态：0待处理 1处理中 2已生效 3处理失败"""
    PENDING = 0
    PROCESSING = 1
    READY = 2
    FAILED = 3

    TEXT = {PENDING: "待处理", PROCESSING: "处理中", READY: "已生效", FAILED: "处理失败"}


class SessionStatus:
    """会话状态：0已结束 1进行中"""
    ENDED = 0
    ACTIVE = 1


class MessageRole:
    """消息角色"""
    USER = "user"
    ASSISTANT = "assistant"


class ToolStatus:
    """工具状态：0禁用 1启用"""
    DISABLED = 0
    ENABLED = 1
