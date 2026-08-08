"""发现流 / 分类列表：/api/vodlist?cat=&source=&api_url=&pg=

聚合多个源的最新列表（ac=videolist），可选按大类过滤。
前端 discovery.js 的 type_name 关键词分类逻辑后移到此处。
"""

import asyncio
import re

import httpx
from fastapi import HTTPException

from . import db
from .config import REQUEST_TIMEOUT, USER_AGENT
from .security import validate_target_url
from .sites import build_list_url, parse_sources
from .textutil import normalize_remarks

_client = httpx.AsyncClient(follow_redirects=True, timeout=REQUEST_TIMEOUT)

# 与前端 discovery.js classifyType 保持一致
# series 用"剧(?![情片])"排除"剧情片/喜剧片"等电影类型误入剧集
_CAT_PATTERNS: dict[str, re.Pattern] = {
    "anime": re.compile(r"动漫|动画|番剧"),
    "variety": re.compile(r"综艺|真人秀|选秀|音乐节目"),
    "series": re.compile(r"剧(?![情片])"),
}
_VALID_CATS = {"movie", "series", "anime", "variety"}


def classify_type(type_name: str | None) -> str:
    t = type_name or ""
    for cat, pat in _CAT_PATTERNS.items():
        if pat.search(t):
            return cat
    return "movie"


async def _fetch_list(api_base: str, pg: int, extra: dict | None = None) -> list[dict]:
    url = build_list_url(api_base, pg, extra)
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


async def get_vodlist(
    cat: str | None = None,
    source: str | None = None,
    api_url: str | None = None,
    pg: int = 1,
    t: str | None = None,
    h: str | None = None,
    by: str | None = None,
    order: str | None = None,
    zy: str | None = None,
    year: str | None = None,
    area: str | None = None,
    lang: str | None = None,
) -> dict:
    """分类 / 列表聚合。

    cat/source/pg 语义保持不变：
    - cat 为本地 type_name 过滤（post-filter）
    - source 指定数据源（逗号分隔 / custom+api_url）
    - pg 页码（唯一默认透传给资源站的参数）

    主路径：无透传参数且非自定义源时查 videos 镜像表（SQL 过滤 + 分页，不打资源站）；
    表为空、自定义源、或带 CMS 透传参数（t/h/by/order/zy/year/area/lang）时走实时拉取。
    新增 CMS V10 透传参数：按白名单原样拼到上游 URL。注意 t=分类ID 各站不统一，
    仅对单一 source 请求有意义，聚合多源时勿用。
    """
    if cat and cat not in _VALID_CATS:
        raise HTTPException(400, f"无效的分类: {cat}")
    if pg < 1:
        raise HTTPException(400, "页码必须大于 0")

    is_custom = bool(source and str(source).startswith("custom"))
    has_passthrough = any(v is not None for v in (t, h, by, order, zy, year, area, lang))

    # 主路径：查 videos 镜像表（仅普通内置源 + 无透传参数时）
    if not is_custom and not has_passthrough:
        src_keys = [s for s in str(source or "").split(",") if s] or None
        rows = db.query_videos_by_source(src_keys)
        if rows:
            items = _dedup(rows)
            if cat:
                items = [it for it in items if classify_type(it.get("type_name")) == cat]
            page_size = 24  # 与前端分类页分页一致
            start = (pg - 1) * page_size
            page_items = items[start : start + page_size]
            return {"total": len(items), "items": page_items, "page": pg}
        # 表为空 → 落到实时兜底

    try:
        sources = parse_sources(source, api_url)
    except ValueError as e:
        raise HTTPException(400, str(e))

    if is_custom and api_url:
        await validate_target_url(api_url)

    extra = {"t": t, "h": h, "by": by, "order": order,
             "zy": zy, "year": year, "area": area, "lang": lang}
    lists = await asyncio.gather(*[_fetch_list(v["api"], pg, extra) for _, v in sources])
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
