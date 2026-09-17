#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Agent 鉴权依赖离线校验（不需要 Redis / MySQL 在线）

只断言**不触碰数据库的路径**：缺少/非法身份头时的 401、以及可选鉴权对游客的放行判定。
覆盖 app/common/identity.py 与 app/api/session.py、chat.py 的依赖接线是否正确——
接线错了（例如忘了挂 Depends）会直接放行，这类问题在真实接口上很难发现。

用法：.venv/Scripts/python.exe scripts/test_agent_identity.py
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from fastapi.testclient import TestClient  # noqa: E402
from app.main import app  # noqa: E402

PASS = 0
FAIL = 0


def check(name, actual, expected):
    global PASS, FAIL
    if actual == expected:
        print(f"  PASS  {name}")
        PASS += 1
    else:
        print(f"  FAIL  {name}\n        期望 {expected!r}\n        实际 {actual!r}")
        FAIL += 1


client = TestClient(app)

print("== 1. 会话接口：无身份头必须 401 ==")
for method, url in (("get", "/api/agent/session/list"),
                    ("post", "/api/agent/session/create"),
                    ("get", "/api/agent/session/1/messages"),
                    ("delete", "/api/agent/session/delete/1")):
    r = getattr(client, method)(url)
    body = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
    check(f"{method.upper()} {url} 无身份头 → 401", body.get("code"), 401)

print("\n== 2. 会话接口：自报 user_id 不能替代身份（query 参数已无效）==")
r = client.get("/api/agent/session/list?user_id=1")
check("带 ?user_id=1 仍 401（自报身份不被信任）", r.json().get("code"), 401)

print("\n== 3. 非法身份头不得当作有效身份 ==")
check("X-User-Id: abc → 401", client.get("/api/agent/session/list",
                                      headers={"X-User-Id": "abc"}).json().get("code"), 401)
check("X-User-Id: 0 → 401（0 是游客，不是登录用户）",
      client.get("/api/agent/session/list", headers={"X-User-Id": "0"}).json().get("code"), 401)
check("X-User-Id: -1 → 401",
      client.get("/api/agent/session/list", headers={"X-User-Id": "-1"}).json().get("code"), 401)

print("\n== 4. identity 依赖单元校验（纯函数，不经过 HTTP）==")
import asyncio  # noqa: E402
from app.common.identity import optional_user_id, required_user_id, GUEST_USER_ID  # noqa: E402
from app.common.exceptions import UnauthorizedException  # noqa: E402

check("游客：optional_user_id(None) = 0", asyncio.run(optional_user_id(None)), GUEST_USER_ID)
check("游客：optional_user_id('') = 0", asyncio.run(optional_user_id("")), GUEST_USER_ID)
check("登录：optional_user_id('123') = 123", asyncio.run(optional_user_id("123")), 123)
check("雪花 ID 字符串可解析", asyncio.run(optional_user_id("2084170013171367937")), 2084170013171367937)
check("非法值按游客处理", asyncio.run(optional_user_id("abc")), GUEST_USER_ID)

try:
    asyncio.run(required_user_id(None))
    check("required_user_id(None) 抛 401", "未抛出", "UnauthorizedException")
except UnauthorizedException as e:
    check("required_user_id(None) 抛 401", e.code, 401)
try:
    asyncio.run(required_user_id("0"))
    check("required_user_id('0') 抛 401（游客不是登录用户）", "未抛出", "UnauthorizedException")
except UnauthorizedException as e:
    check("required_user_id('0') 抛 401（游客不是登录用户）", e.code, 401)
check("required_user_id('456') = 456", asyncio.run(required_user_id("456")), 456)

print("\n== 5. 知识库 / 工具配置：必须是管理员 ==")
for method, url in (("get", "/api/agent/knowledge/list"),
                    ("delete", "/api/agent/knowledge/delete/1"),
                    ("get", "/api/agent/tool/list"),
                    ("put", "/api/agent/tool/status/1")):
    r = getattr(client, method)(url)
    body = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
    check(f"{method.upper()} {url} 无身份头 → 401", body.get("code"), 401)

# 已登录但非管理员：403（此前这类接口只要求登录，任何用户都能删库停工具）
r = client.get("/api/agent/knowledge/list", headers={"X-User-Id": "123", "X-User-Role": "USER"})
check("普通用户访问知识库 → 403", r.json().get("code"), 403)
r = client.get("/api/agent/tool/list", headers={"X-User-Id": "123", "X-User-Role": "USER"})
check("普通用户访问工具配置 → 403", r.json().get("code"), 403)
r = client.request("PUT", "/api/agent/tool/status/1", json={"status": 0},
                   headers={"X-User-Id": "123", "X-User-Role": "USER"})
check("普通用户启停工具 → 403", r.json().get("code"), 403)
# 缺失角色头同样按非管理员处理（不能因为没传角色就放行）
r = client.get("/api/agent/knowledge/list", headers={"X-User-Id": "123"})
check("登录但缺角色头 → 403（默认不是管理员）", r.json().get("code"), 403)

print("\n== 6. required_admin 依赖单元校验 ==")
from app.common.identity import required_admin, ROLE_ADMIN  # noqa: E402
from app.common.exceptions import ForbiddenException  # noqa: E402

check("ADMIN 通过", asyncio.run(required_admin("1", ROLE_ADMIN)), 1)
check("角色大小写不敏感（admin）", asyncio.run(required_admin("1", "admin")), 1)
check("角色带空格容错", asyncio.run(required_admin("1", " ADMIN ")), 1)
try:
    asyncio.run(required_admin("1", "USER"))
    check("USER 抛 403", "未抛出", "ForbiddenException")
except ForbiddenException as e:
    check("USER 抛 403", e.code, 403)
try:
    asyncio.run(required_admin("1", None))
    check("角色缺失抛 403", "未抛出", "ForbiddenException")
except ForbiddenException as e:
    check("角色缺失抛 403", e.code, 403)
try:
    asyncio.run(required_admin(None, "ADMIN"))
    check("未登录优先抛 401（而非 403）", "未抛出", "UnauthorizedException")
except UnauthorizedException as e:
    check("未登录优先抛 401（而非 403）", e.code, 401)

print("\n== 7. 健康检查仍是白名单（无身份可访问，探活依赖它）==")
r = client.get("/api/agent/health")
check("health 不返回 401", r.json().get("code") != 401, True)

print("\n" + "=" * 50)
print(f"结果：PASS={PASS}  FAIL={FAIL}")
print("=" * 50)
sys.exit(0 if FAIL == 0 else 1)
