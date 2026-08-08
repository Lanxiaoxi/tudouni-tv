"""聚合搜索：/api/search?wd=&source=&api_url=&page=

- 不传 source：并行查所有普通源并合并去重（vod_name 同名保留第一个）
- source=key1,key2：只查勾选的内置源（与前端设置勾选对齐）
- source=custom&api_url=xxx：自定义源透传（前端"自定义接口"功能兼容）
- 单源 8s 超时、失败静默降级为 []，不影响整体结果
"""

import asyncio
from urllib.parse import quote

import httpx
from fastapi import HTTPException

from .config import MAX_QUERY_LENGTH, REQUEST_TIMEOUT, USER_AGENT
from .security import validate_target_url
from .sites import SEARCH_PATH, parse_sources
from .textutil import normalize_remarks

_client = httpx.AsyncClient(follow_redirects=True, timeout=REQUEST_TIMEOUT)


async def _fetch_source(api_base: str, wd: str, page: int) -> list[dict]:
    url = api_base.rstrip("/") + SEARCH_PATH + quote(wd)
    if page > 1:
        url += f"&pg={page}"
    try:
        resp = await _client.get(
            url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"}
        )
        if resp.status_code != 200:
            return []
        data = resp.json()
        lst = data.get("list") if isinstance(data, dict) else None
        if not isinstance(lst, list):
            return []
        # 统一规范化 vod_remarks（"第N集已完结" → "共N集已完结"）
        for it in lst:
            if isinstance(it, dict) and it.get("vod_remarks"):
                it["vod_remarks"] = normalize_remarks(it["vod_remarks"])
        return lst
    except Exception:
        return []


def _dedup(items: list[dict]) -> list[dict]:
    seen: set[str] = set()
    out: list[dict] = []
    for it in items:
        name = str(it.get("vod_name") or "")
        if name in seen:
            continue
        seen.add(name)
        out.append(it)
    return out


async def aggregated_search(
    wd: str,
    source: str | None = None,
    api_url: str | None = None,
    page: int = 1,
) -> dict:
    wd = wd.strip()
    if not wd:
        raise HTTPException(400, "缺少搜索关键词")
    if len(wd) > MAX_QUERY_LENGTH:
        raise HTTPException(400, "搜索词过长")
    if page < 1:
        raise HTTPException(400, "页码必须大于 0")

    try:
        sources = parse_sources(source, api_url)
    except ValueError as e:
        raise HTTPException(400, str(e))

    # 自定义源 URL 需先过 SSRF 校验
    if source and source.startswith("custom") and api_url:
        await validate_target_url(api_url)

    lists = await asyncio.gather(*[_fetch_source(v["api"], wd, page) for _, v in sources])
    items: list[dict] = []
    for (key, site), lst in zip(sources, lists):
        for it in lst:
            if isinstance(it, dict):
                it["source_name"] = site["name"]
                it["source_code"] = key
                items.append(it)
    items = _dedup(items)
    return {"total": len(items), "items": items, "page": page}
