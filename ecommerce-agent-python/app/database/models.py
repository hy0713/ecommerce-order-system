"""ORM 实体：4 张表，与电商 Java 版表结构完全一致"""
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, Index, Integer, String, Text, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from app.common.utils.snowflake import snowflake


class Base(DeclarativeBase):
    pass


class AgentSession(Base):
    """用户会话表"""
    __tablename__ = "agent_session"
    __table_args__ = (
        Index("idx_user_id", "user_id"),
        {"mysql_charset": "utf8mb4", "mysql_engine": "InnoDB"},
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, default=snowflake.next_id)
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False, comment="关联电商系统用户ID")
    title: Mapped[str] = mapped_column(String(128), nullable=False, default="新会话", comment="会话标题")
    status: Mapped[int] = mapped_column(Integer, nullable=False, default=1, comment="0已结束 1进行中")
    create_time: Mapped[datetime] = mapped_column(DateTime, server_default=func.now(), comment="创建时间")
    update_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now(), comment="更新时间")


class AgentMessage(Base):
    """对话消息表"""
    __tablename__ = "agent_message"
    __table_args__ = (
        Index("idx_session_create", "session_id", "create_time"),
        {"mysql_charset": "utf8mb4", "mysql_engine": "InnoDB"},
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, default=snowflake.next_id)
    session_id: Mapped[int] = mapped_column(BigInteger, nullable=False, comment="所属会话ID")
    role: Mapped[str] = mapped_column(String(16), nullable=False, comment="user/assistant")
    content: Mapped[str] = mapped_column(Text, nullable=False, comment="消息文本")
    tool_calls: Mapped[str | None] = mapped_column(Text, nullable=True, comment="工具调用JSON，仅assistant")
    create_time: Mapped[datetime] = mapped_column(DateTime, server_default=func.now(), comment="创建时间")


class KnowledgeDocument(Base):
    """知识库文档表"""
    __tablename__ = "knowledge_document"
    __table_args__ = {"mysql_charset": "utf8mb4", "mysql_engine": "InnoDB"}

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, default=snowflake.next_id)
    title: Mapped[str] = mapped_column(String(128), nullable=False, comment="文档标题")
    doc_type: Mapped[str] = mapped_column(String(16), nullable=False, comment="pdf/docx/txt/md")
    file_url: Mapped[str] = mapped_column(String(255), nullable=False, comment="文件存储地址")
    chunk_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0, comment="文本分块总数")
    status: Mapped[int] = mapped_column(Integer, nullable=False, default=0, comment="0待处理1处理中2已生效3失败")
    fail_reason: Mapped[str | None] = mapped_column(String(255), nullable=True, comment="失败原因")
    create_time: Mapped[datetime] = mapped_column(DateTime, server_default=func.now(), comment="创建时间")
    update_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now(), comment="更新时间")


class AgentToolConfig(Base):
    """工具配置表"""
    __tablename__ = "agent_tool_config"
    __table_args__ = {"mysql_charset": "utf8mb4", "mysql_engine": "InnoDB"}

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, default=snowflake.next_id)
    tool_name: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, comment="工具唯一标识")
    tool_description: Mapped[str] = mapped_column(String(255), nullable=False, comment="功能描述")
    params_schema: Mapped[str | None] = mapped_column(Text, nullable=True, comment="入参JSON Schema")
    status: Mapped[int] = mapped_column(Integer, nullable=False, default=1, comment="0禁用 1启用")
    create_time: Mapped[datetime] = mapped_column(DateTime, server_default=func.now(), comment="创建时间")
    update_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now(), comment="更新时间")
