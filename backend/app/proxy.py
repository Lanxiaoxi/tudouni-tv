"""通用元数据代理：/api/proxy?url=

带 SSRF 防护、UA/Referer 反盗链、超时、重试与内存 TTL 缓存。
支持任意内容（JSON / 海报图等），按 Content-Type 原样返回。
"""

import asyncio
import time
from urllib.parse import urlparse

import httpx
from fastapi import HTTPException, Query
from fastapi.responses import Response

from .config import CACHE_TTL_SECONDS, MAX_CACHE_BYTES, MAX_RETRIES, REQUEST_TIMEOUT, USER_AGENT
from .security import validate_target_url

_client = httpx.AsyncClient(follow_redirects=True, timeout=REQUEST_TIMEOUT)

# url -> (过期时间戳, body, content_type)
_cache: dict[str, tuple[float, bytes, str]] = {}

# 缓存上限：超过后清理过期项，仍超限则删最早 1/4（防内存无限增长）
_MAX_CACHE_ITEMS = 500

# 转发时剔除的敏感响应头
_FILTERED_HEADERS = {
    "content-security-policy", "set-cookie", "x-frame-options",
    "access-control-allow-origin", "transfer-encoding",
}


async def _fetch_with_retry(url: str) -> tuple[bytes, str]:
    parsed = urlparse(url)
    referer = f"{parsed.scheme}://{parsed.netloc}"
    headers = {
        "User-Agent": USER_AGENT,
        "Referer": referer,
        "Accept": "*/*",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    }
    last_err: Exception | None = None
    for attempt in range(MAX_RETRIES + 1):
        try:
            resp = await _client.get(url, headers=headers)
            if resp.status_code >= 400:
                raise HTTPException(502, f"上游返回 {resp.status_code}")
            return resp.content, resp.headers.get("content-type", "application/octet-stream")
        except httpx.HTTPError as e:  # 网络错误/超时才重试
            last_err = e
            if attempt < MAX_RETRIES:
                await asyncio.sleep(0.3 * (attempt + 1))
    raise HTTPException(502, f"代理请求失败: {last_err}")


def _trim_cache(now: float) -> None:
    """缓存超过上限时清理过期项；仍超限则删除最早的 1/4。"""
    if len(_cache) <= _MAX_CACHE_ITEMS:
        return
    expired = [k for k, v in _cache.items() if v[0] <= now]
    for k in expired:
        del _cache[k]
    if len(_cache) > _MAX_CACHE_ITEMS:
        ordered = sorted(_cache.items(), key=lambda kv: kv[1][0])
        for k, _ in ordered[: max(1, len(ordered) // 4)]:
            del _cache[k]


async def proxy_url(url: str) -> Response:
    await validate_target_url(url)

    now = time.time()
    hit = _cache.get(url)
    if hit and hit[0] > now:
        body, content_type = hit[1], hit[2]
    else:
        body, content_type = await _fetch_with_retry(url)
        if len(body) <= MAX_CACHE_BYTES:
            _cache[url] = (now + CACHE_TTL_SECONDS, body, content_type)
            _trim_cache(now)

    headers = {"Content-Type": content_type, "Cache-Control": "public, max-age=60"}
    return Response(content=body, headers=headers)
