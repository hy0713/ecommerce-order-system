"""电商系统 HTTP 客户端：httpx 异步封装，统一鉴权头与异常处理

连接复用：进程内共享一个 AsyncClient（懒加载），避免每次调用重建连接池。
"""
import logging
from typing import Optional

import httpx

from app.config import get_settings

logger = logging.getLogger("app.ecom")

_settings = get_settings()

TIMEOUT = httpx.Timeout(15.0, connect=5.0)
# 连接池上限，防止高并发下无限建连
LIMITS = httpx.Limits(max_connections=50, max_keepalive_connections=20)


class EcomClient:
    def __init__(self, base_url: Optional[str] = None):
        self.base_url = (base_url or _settings.ecom_gateway_base).rstrip("/")
        self._client: Optional[httpx.AsyncClient] = None

    def _shared_client(self) -> httpx.AsyncClient:
        """懒加载共享客户端（在事件循环内首次创建）"""
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(timeout=TIMEOUT, limits=LIMITS)
        return self._client

    async def aclose(self) -> None:
        """应用关闭时释放连接池"""
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()
        self._client = None

    async def get(self, path: str, token: Optional[str] = None, params: dict = None) -> dict:
        """GET 并解包 Result{code,message,data}；网络/协议异常返回友好结构"""
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        try:
            resp = await self._shared_client().get(
                f"{self.base_url}{path}", headers=headers, params=params)
        except httpx.HTTPError as e:
            logger.warning("电商接口调用失败 %s: %s", path, e)
            return {"code": 500, "message": f"电商系统连接失败：{type(e).__name__}", "data": None}
        return self._parse(resp, path)

    @staticmethod
    def _parse(resp: httpx.Response, path: str) -> dict:
        try:
            body = resp.json()
        except Exception:
            logger.warning("电商接口 %s 返回非 JSON（HTTP %s）", path, resp.status_code)
            return {"code": 500, "message": "电商系统返回异常", "data": None}
        if not isinstance(body, dict) or "code" not in body:
            return {"code": 500, "message": "电商系统响应格式异常", "data": body}
        if resp.status_code == 401:
            body["message"] = body.get("message") or "登录已过期，请重新登录"
        return body


ecom_client = EcomClient()
