"""爱奇艺热播榜单：/api/iqiyi/hot

设计（方案 C：惰性缓存 + 定时预热）：
- 爱奇艺只提供**片名**（热播榜 tag=0，无 m3u8），实际播放数据走现有资源站聚合搜索
  （search.aggregated_search 按片名搜，命中即得带 m3u8 的完整条目）——链路与普通搜索一致
- 定时任务每 30min 预热一次：拉榜单 → 限流并发搜索前 20 片名 → 取每条第一个命中 → 写缓存
- 端点先查缓存：命中直接返回（毫秒级）；未命中（冷启动）现场刷新一次并写回
- 限流：asyncio.Semaphore，避免 20 片名并发全源搜索打爆资源站
- 失败降级：任何异常返回空列表，绝不阻塞首页其他内容行

缓存 key 为全局聚合（无用户维度），与 /api/items 一致，可安全用进程内 TTL 缓存。
"""

import asyncio
import logging
import time

import httpx

from . import search
from .cache import cache_get, cache_set
from .config import REQUEST_TIMEOUT, USER_AGENT

logger = logging.getLogger("libretv")

# 爱奇艺榜单接口与参数（见文档 topRanksData）
_IQIYI_RANK_URL = "https://pcw-api.iqiyi.com/strategy/pcw/data/topRanksData"
_IQIYI_TAG = 0          # 热播榜
_IQIYI_CATEGORY_ID = 1  # 全站
_IQIYI_TOP_N = 20       # 取榜单前 20 片名去搜索

# 缓存与预热
_IQIYI_TTL_SECONDS = 30 * 60   # 缓存 30 分钟（榜单变化不频繁）
_IQIYI_REFRESH_INTERVAL = 30 * 60  # 后台预热间隔
_IQIYI_CONCURRENCY = 4         # 并发搜索限流

_CACHE_KEY = "iqiyi:hot"

_client = httpx.AsyncClient(
    follow_redirects=True,
    timeout=REQUEST_TIMEOUT,
    headers={"User-Agent": USER_AGENT},
)

_semaphore = asyncio.Semaphore(_IQIYI_CONCURRENCY)


async def _fetch_rank_titles() -> list[str]:
    """拉取爱奇艺热播榜，返回去重后的片名列表（最多 TOP_N 个）。

    Raises:
        HTTPError: 网络/状态码异常（由调用方统一降级）
    """
    resp = await _client.get(
        _IQIYI_RANK_URL,
        params={
            "page_st": 0,
            "pg_num": 1,
            "tag": _IQIYI_TAG,
            "category_id": _IQIYI_CATEGORY_ID,
            "date": "",
        },
    )
    resp.raise_for_status()
    payload = resp.json()
    if payload.get("code") != "A00000":
        logger.warning("爱奇艺榜单返回异常 code=%s", payload.get("code"))
        return []

    content = (
        payload.get("data", {})
        .get("formatData", {})
        .get("data", {})
        .get("content", [])
    )
    titles: list[str] = []
    seen: set[str] = set()
    for item in content:
        title = (item.get("title") or "").strip()
        if title and title not in seen:
            seen.add(title)
            titles.append(title)
        if len(titles) >= _IQIYI_TOP_N:
            break
    return titles


async def _search_one(title: str) -> dict | None:
    """按片名走资源站聚合搜索，返回第一条命中（含 m3u8 的完整条目）。失败返回 None。"""
    async with _semaphore:
        try:
            data = await search.aggregated_search(title)
            items = data.get("items") or []
            return items[0] if items else None
        except Exception as exc:  # noqa: BLE001 单个片名失败不影响整体
            logger.warning("爱奇艺片名搜索失败 title=%s err=%s", title, exc)
            return None


async def refresh() -> dict:
    """完整刷新：拉榜单 → 限流并发搜索 → 按 (source, vod_id) 去重 → 结果 dict。

    与 /api/items 的返回结构对齐（{items, total, updated_at}），便于前端复用内容行。
    """
    titles = await _fetch_rank_titles()
    if not titles:
        return {"items": [], "total": 0, "updated_at": int(time.time())}

    results = await asyncio.gather(*[_search_one(t) for t in titles])
    items: list[dict] = []
    seen: set[tuple[str, str]] = set()
    for it in results:
        if not it:
            continue
        # 同剧可能被多个片名/多源命中，按 (source_code, vod_id) 去重保留第一个
        key = (str(it.get("source_code") or ""), str(it.get("vod_id") or ""))
        if key in seen:
            continue
        seen.add(key)
        items.append(it)

    return {"items": items, "total": len(items), "updated_at": int(time.time())}


async def get_hot() -> dict:
    """端点入口：缓存命中直接返回；未命中现场刷新并写回。

    任何异常都返回空列表（首页内容行拿不到数据就隐藏，不影响其他行）。
    """
    cached = cache_get(_CACHE_KEY)
    if cached is not None:
        return cached
    try:
        result = await refresh()
    except Exception as exc:  # noqa: BLE001
        logger.error("爱奇艺热播刷新失败: %s", exc)
        return {"items": [], "total": 0, "updated_at": None}
    cache_set(_CACHE_KEY, result, _IQIYI_TTL_SECONDS)
    return result


async def warmup_loop() -> None:
    """后台预热循环（main.py lifespan 启动）：冷启动刷一次，之后按间隔刷新。"""
    try:
        result = await refresh()
        cache_set(_CACHE_KEY, result, _IQIYI_TTL_SECONDS)
        logger.info("爱奇艺热播预热完成: %s 条", result.get("total", 0))
    except Exception as exc:  # noqa: BLE001
        logger.error("爱奇艺热播预热失败: %s", exc)

    while True:
        await asyncio.sleep(_IQIYI_REFRESH_INTERVAL)
        try:
            result = await refresh()
            cache_set(_CACHE_KEY, result, _IQIYI_TTL_SECONDS)
            logger.info("爱奇艺热播定时刷新完成: %s 条", result.get("total", 0))
        except Exception as exc:  # noqa: BLE001
            logger.error("爱奇艺热播定时刷新失败: %s", exc)
