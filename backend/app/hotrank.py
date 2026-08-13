"""热播榜单通用框架：榜单片名 → 资源站聚合搜索 → 内存缓存 + 数据库条目并集 + 后台预热。

各平台（爱奇艺/优酷/腾讯…）只需提供「片名抓取」回调，其余链路完全一致：
- 定时预热：每 REFRESH_INTERVAL（默认 5 天）刷一次榜单（拉片名 → 限流并发搜索 → 去重 → 写缓存）
- 端点：缓存命中直接返回（毫秒级）；未命中（冷启动）现场刷新一次并写回
- 持久化兜底：每次刷新成功把条目**并集合并**进 SQLite（hot_rank_items，按 source+vod_id 去重：
  这次 abc、下次 bcd → 库里累积 abcd），当榜单拉取失败且内存缓存过期时，
  从数据库取累积条目返回，保证首页该行不空
- 限流：asyncio.Semaphore，避免 N 个片名并发全源搜索打爆资源站
- 失败降级：任何异常返回空列表（或数据库兜底），绝不阻塞首页其他内容行

内存缓存 key 为全局聚合（无用户维度），与 /api/items 一致。
"""

import asyncio
import logging
import time

import httpx

from . import db, search
from .cache import cache_get, cache_set
from .config import REQUEST_TIMEOUT, USER_AGENT

logger = logging.getLogger("libretv")

_client = httpx.AsyncClient(
    follow_redirects=True,
    timeout=REQUEST_TIMEOUT,
    headers={"User-Agent": USER_AGENT},
)


class HotRankSource:
    """单个榜单源：绑定 fetch_titles 回调，提供 refresh / get_hot / warmup_loop。"""

    def __init__(
        self,
        name: str,
        cache_key: str,
        fetch_titles,
        top_n: int = 20,
        ttl_seconds: int = 5 * 24 * 3600,      # 缓存 5 天（热播榜更新不频繁）
        refresh_interval: int = 5 * 24 * 3600,  # 后台预热间隔 5 天（与 TTL 对齐）
        concurrency: int = 4,
    ) -> None:
        self.name = name
        self._cache_key = cache_key
        self._fetch_titles = fetch_titles
        self._top_n = top_n
        self._ttl_seconds = ttl_seconds
        self._refresh_interval = refresh_interval
        self._semaphore = asyncio.Semaphore(concurrency)

    async def _search_one(self, title: str) -> dict | None:
        """按片名走资源站聚合搜索，返回第一条命中（含 m3u8 的完整条目）。失败返回 None。"""
        async with self._semaphore:
            try:
                data = await search.aggregated_search(title)
                items = data.get("items") or []
                return items[0] if items else None
            except Exception as exc:  # noqa: BLE001 单个片名失败不影响整体
                logger.warning("%s 片名搜索失败 title=%s err=%s", self.name, title, exc)
                return None

    async def refresh(self) -> dict:
        """完整刷新：拉片名 → 限流并发搜索 → 按 (source, vod_id) 去重 → 结果 dict。

        与 /api/items 的返回结构对齐（{items, total, updated_at}），便于前端复用内容行。
        刷新成功（items 非空）时把条目并集合并进数据库，供后续拉取失败时兜底。
        """
        titles = await self._fetch_titles()
        titles = [t for t in titles if t][: self._top_n]
        if not titles:
            return {"items": [], "total": 0, "updated_at": int(time.time())}

        results = await asyncio.gather(*[self._search_one(t) for t in titles])
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

        result = {"items": items, "total": len(items), "updated_at": int(time.time())}
        if items:  # 空结果不落库，避免覆盖历史有效条目
            try:
                db.merge_hot_rank_items(self.name, items)
            except Exception as exc:  # noqa: BLE001 落库失败不影响本次返回
                logger.warning("%s 热播条目落库失败: %s", self.name, exc)
        return result

    async def get_hot(self) -> dict:
        """端点入口：缓存命中直接返回；未命中现场刷新并写回；
        刷新失败则从数据库取累积条目兜底（再无则空列表）。"""
        cached = cache_get(self._cache_key)
        if cached is not None:
            return cached
        try:
            result = await self.refresh()
        except Exception as exc:  # noqa: BLE001
            logger.error("%s 热播刷新失败: %s", self.name, exc)
            fallback_items = db.get_hot_rank_items(self.name)
            if fallback_items:
                logger.info("%s 热播使用数据库条目兜底: %s 条", self.name, len(fallback_items))
                return {
                    "items": fallback_items,
                    "total": len(fallback_items),
                    "updated_at": int(time.time()),
                }
            return {"items": [], "total": 0, "updated_at": None}
        cache_set(self._cache_key, result, self._ttl_seconds)
        return result

    async def warmup_loop(self) -> None:
        """后台预热循环（main.py lifespan 启动）：冷启动刷一次，之后按间隔刷新。"""
        try:
            result = await self.refresh()
            cache_set(self._cache_key, result, self._ttl_seconds)
            logger.info("%s 热播预热完成: %s 条", self.name, result.get("total", 0))
        except Exception as exc:  # noqa: BLE001
            logger.error("%s 热播预热失败: %s", self.name, exc)

        while True:
            await asyncio.sleep(self._refresh_interval)
            try:
                result = await self.refresh()
                cache_set(self._cache_key, result, self._ttl_seconds)
                logger.info("%s 热播定时刷新完成: %s 条", self.name, result.get("total", 0))
            except Exception as exc:  # noqa: BLE001
                logger.error("%s 热播定时刷新失败: %s", self.name, exc)
