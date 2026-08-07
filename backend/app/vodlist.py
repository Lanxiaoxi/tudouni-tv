"""发现流 / 分类列表：/api/vodlist?cat=&source=&api_url=&pg=

聚合多个源的最新列表（ac=videolist），可选按大类过滤。
前端 discovery.js 的 type_name 关键词分类逻辑后移到此处。
"""

import asyncio

import httpx
from fastapi import HTTPException

from .config import REQUEST_TIMEOUT, USER_AGENT
from .security import validate_target_url
from .sites import LIST_PATH, parse_sources

_client = httpx.AsyncClient(follow_redirects=True, timeout=REQUEST_TIMEOUT)

# 与前端 discovery.js classifyType 保持一致
_CAT_KEYWORDS: dict[str, tuple[str, ...]] = {
    "anime": ("动漫", "动画", "番剧"),
    "variety": ("综艺", "真人秀", "选秀", "音乐节目"),
    "series": ("剧", "连续剧", "电视剧", "短剧", "剧场"),
}
_VALID_CATS = {"movie", "series", "anime", "variety"}


def classify_type(type_name: str | None) -> str:
    t = type_name or ""
    for cat, kws in _CAT_KEYWORDS.items():
        if any(k in t for k in kws):
            return cat
    return "movie"


async def _fetch_list(api_base: str, pg: int) -> list[dict]:
    url = api_base.rstrip("/") + LIST_PATH + str(pg)
    try:
        resp = await _client.get(
            url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"}
        )
        if resp.status_code != 200:
            return []
        data = resp.json()
        lst = data.get("list") if isinstance(data, dict) else None
        return lst if isinstance(lst, list) else []
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


async def get_vodlist(
    cat: str | None = None,
    source: str | None = None,
    api_url: str | None = None,
    pg: int = 1,
) -> dict:
    if cat and cat not in _VALID_CATS:
        raise HTTPException(400, f"无效的分类: {cat}")
    if pg < 1:
        raise HTTPException(400, "页码必须大于 0")

    try:
        sources = parse_sources(source, api_url)
    except ValueError as e:
        raise HTTPException(400, str(e))

    if source and source.startswith("custom") and api_url:
        await validate_target_url(api_url)

    lists = await asyncio.gather(*[_fetch_list(v["api"], pg) for _, v in sources])
    items: list[dict] = []
    for (key, site), lst in zip(sources, lists):
        for it in lst:
            if isinstance(it, dict):
                it["source_name"] = site["name"]
                it["source_code"] = key
                items.append(it)
    items = _dedup(items)
    if cat:
        items = [it for it in items if classify_type(it.get("type_name")) == cat]
    return {"total": len(items), "items": items, "page": pg}
