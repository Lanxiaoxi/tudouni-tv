"""聚合搜索：/api/search?wd=&source=&api_url=&page=

三级链路（减少对资源站的实时请求）：
① TTL 缓存：同词 3-5 分钟内命中直接返回（存的是最终聚合结果）
② videos 镜像表：本地 title 模糊搜索（仅 pg=1），命中即返回并写缓存
③ 实时兜底：①②都未命中才并发打资源站，聚合后写缓存

- 不传 source：并行查所有普通源并合并去重（vod_name 同名保留第一个）
- source=key1,key2：只查勾选的内置源（与前端设置勾选对齐）
- source=custom&api_url=xxx：自定义源透传（走实时，不缓存/不走本地表）
- 单源 8s 超时、失败静默降级为 []，不影响整体结果
"""

import asyncio
from urllib.parse import quote

import httpx
from fastapi import HTTPException

from . import db
from .cache import cache_get, cache_set
from .config import MAX_QUERY_LENGTH, REQUEST_TIMEOUT, SEARCH_TTL_SECONDS, USER_AGENT
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

    is_custom = bool(source and str(source).startswith("custom"))
    cache_key = f"search:{wd}:{source or 'all'}:{page}"

    if not is_custom:
        # 一级：TTL 缓存命中直接返回
        cached = cache_get(cache_key)
        if cached is not None:
            return cached

        # 二级：本地 videos 镜像表（仅首页，标题模糊匹配）
        if page == 1:
            src_keys = [s for s in str(source or "").split(",") if s] or None
            local = db.search_videos_local(wd, src_keys, limit=20)
            if local:
                items = _dedup(local)
                result = {"total": len(items), "items": items, "page": page}
                cache_set(cache_key, result, SEARCH_TTL_SECONDS)
                return result

    # 三级：实时拉取（或 custom 源直通）
    try:
        sources = parse_sources(source, api_url)
    except ValueError as e:
        raise HTTPException(400, str(e))

    # 自定义源 URL 需先过 SSRF 校验
    if is_custom and api_url:
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
    result = {"total": len(items), "items": items, "page": page}
    if not is_custom:
        # 按需填充：实时兜底结果写入 videos 表（用户搜过的片进库，下次直接本地命中）
        try:
            rows = [
                {
                    "source": it.get("source_code", ""),
                    "source_name": it.get("source_name"),
                    "vod_id": str(it.get("vod_id") or ""),
                    "title": str(it.get("vod_name") or ""),
                    "type_name": it.get("type_name"),
                    "pic": it.get("vod_pic"),
                    "remarks": it.get("vod_remarks"),
                    "area": it.get("vod_area"),
                    "year": it.get("vod_year"),
                    "play_url": it.get("vod_play_url"),
                    "vod_time": it.get("vod_time"),
                }
                for it in items
            ]
            db.upsert_videos(rows)
        except Exception:
            pass  # 入库失败不影响搜索返回
        cache_set(cache_key, result, SEARCH_TTL_SECONDS)
    return result
