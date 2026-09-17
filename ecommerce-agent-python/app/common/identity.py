"""调用方身份解析：身份一律取自网关注入的 ``X-User-Id`` 请求头。

安全约定（改动前务必阅读）
--------------------------
本服务**不接受调用方自报的 user_id**（query/body 里的一律忽略）：
既然 user_id 决定「能读谁的会话」，自报就等于任意用户可读他人聊天记录。

网关在转发前会先剥离客户端伪造的 ``X-User-Id``，再写入 token 校验得到的真实身份
（见 shop-gateway ``AuthGlobalFilter.sanitize`` 与 ``AuthConstant.OPTIONAL_AUTH_ENDPOINTS``），
因此本服务**必须部署在网关之后**，:8000 不应对外暴露。

对应鉴权策略：
- ``POST /api/agent/chat``：可选鉴权 → 无身份头视为游客（``GUEST_USER_ID``）；
- ``/api/agent/session/**``：必须登录 → 无身份头直接 401；
- ``/api/agent/knowledge/**``、``/api/agent/tool/**``：必须**管理员** → 非 ADMIN 返回 403。
"""
from typing import Optional

from fastapi import Header

from app.common.exceptions import ForbiddenException, UnauthorizedException

HEADER_USER_ID = "X-User-Id"
HEADER_USER_ROLE = "X-User-Role"

#: 管理员角色（与 Java 侧 AuthConstant.ROLE_ADMIN 一致）
ROLE_ADMIN = "ADMIN"

#: 游客身份：允许做知识问答，但查不到任何人的订单与会话
GUEST_USER_ID = 0


def _to_user_id(raw: Optional[str]) -> Optional[int]:
    """解析身份头；非数字视为无效身份（返回 None）"""
    if raw is None or str(raw).strip() == "":
        return None
    try:
        return int(str(raw).strip())
    except (TypeError, ValueError):
        return None


async def optional_user_id(
    x_user_id: Optional[str] = Header(default=None, alias=HEADER_USER_ID),
) -> int:
    """可选身份：无网关注入的身份头 = 游客。用于允许匿名访问的对话接口"""
    uid = _to_user_id(x_user_id)
    return GUEST_USER_ID if uid is None or uid <= 0 else uid


async def required_user_id(
    x_user_id: Optional[str] = Header(default=None, alias=HEADER_USER_ID),
) -> int:
    """必须身份：缺失或非法即 401。用于会话列表 / 历史消息 / 删除等涉及他人数据的接口"""
    uid = _to_user_id(x_user_id)
    if uid is None or uid <= 0:
        raise UnauthorizedException()
    return uid


async def required_admin(
    x_user_id: Optional[str] = Header(default=None, alias=HEADER_USER_ID),
    x_user_role: Optional[str] = Header(default=None, alias=HEADER_USER_ROLE),
) -> int:
    """必须管理员：未登录 401、已登录但非 ADMIN 403。

    用于知识库与工具配置等**管理动作**（上传/删除文档、启停工具）——
    之前这些接口只要求登录，任何普通用户都能删库停工具。
    """
    uid = _to_user_id(x_user_id)
    if uid is None or uid <= 0:
        raise UnauthorizedException()
    if (x_user_role or "").strip().upper() != ROLE_ADMIN:
        raise ForbiddenException("仅管理员可操作知识库与工具配置")
    return uid
