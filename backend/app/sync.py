"""资源镜像表定时同步：拉资源站列表写入 videos 表。

同步策略（平衡「最新」与「增量积累」）：
- 每天每源拉 SYNC_PAGES_PER_SOURCE 页（默认 10）
- 前 SYNC_FRESH_PAGES 页（默认 2）每天必拉 → 保证首页/分类始终最新
- 剩余页按 SYNC_DEEP_CYCLE_DAYS（默认 15）天轮转 → 每天换一个深区，覆盖
  (总页数-新鲜页)×周期 页深度，让老片慢慢积累（增量），请求量不变

稳健性保证（用户要求）：
- 单页失败跳过继续下一页，不影响该源剩余页
- 连续 2 页空 → 提前 break（源站已到底/暂时无数据，停止空拉浪费请求）
- 单源整体失败仅记入 failed，不影响其他源；整个任务异常由调用方（_sync_loop）兜底
- upsert 按 (source, vod_id) 去重，重复/超限页无害

清理：同步完成后删除 SYNC_STALE_DAYS 天未更新的残留行，但保护「任意用户观看历史里
出现过的内容」（viewing_history.source 存源中文名，需映射回 key 与 videos.source 匹配）。
"""

import asyncio
import hashlib
import os
import time

from . import db
from .config import (
    SYNC_DEEP_CYCLE_DAYS,
    SYNC_FRESH_PAGES,
    SYNC_PAGES_PER_SOURCE,
    SYNC_STALE_DAYS,
)
from .sites import SITES
from .vodlist import _fetch_list

# 连续空页达到该值即提前退出（源站到底或异常）
_EMPTY_BREAK_STREAK = 2

# 封面本地化：covers/ 目录（仓库根）按 URL 哈希缓存封面图
_BACKEND_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_COVERS_DIR = os.path.join(_BACKEND_DIR, "..", "covers")
_COVER_EXTS = (".jpg", ".png", ".webp")


def pic_local(url: str | None) -> str | None:
    """封面 URL 本地化：covers/ 已有该 URL 的缓存文件则返回本地路径，否则原样返回 URL。

    新封面先存 URL（download_covers.py 补齐后，下次同步自动转为本地路径），
    避免同步流程内串行下载拖慢拉取。
    """
    if not url or not str(url).startswith("http"):
        return url
    name = hashlib.sha256(str(url).encode()).hexdigest()[:16]
    for ext in _COVER_EXTS:
        p = os.path.join(_COVERS_DIR, name + ext)
        if os.path.exists(p) and os.path.getsize(p) > 0:
            return f"/covers/{name}{ext}"
    return url


def _build_page_set() -> list[int]:
    """构造今天要拉的页集合：最新页(1..fresh) + 轮转深区(深区起点..起点+deep-1)。

    轮转偏移用「本地日序号 % 周期」计算——无状态，重启不丢进度。
    """
    total = SYNC_PAGES_PER_SOURCE
    fresh = min(max(SYNC_FRESH_PAGES, 0), total)
    deep_pages = max(total - fresh, 0)
    if deep_pages == 0:
        return list(range(1, total + 1))
    # 本地日序号（东八区），单调递增，重启/跨年都稳定
    day_index = int((time.time() + 8 * 3600) // 86400) % SYNC_DEEP_CYCLE_DAYS
    deep_start = fresh + 1 + day_index * deep_pages
    deep_end = deep_start + deep_pages - 1
    return list(range(1, fresh + 1)) + list(range(deep_start, deep_end + 1))


async def sync_all_sources(pages: int | None = None) -> dict:
    """同步全部普通源到 videos 表，并清理过期残留。返回统计信息。

    pages 参数仅测试用：传入时退化为「拉前 pages 页」的简单模式。
    """
    sources = [(k, v) for k, v in SITES.items() if not v["adult"]]
    stats: dict = {"sources": len(sources), "items": 0, "failed": [], "cleaned": 0, "protected": 0}
    page_set = list(range(1, pages + 1)) if pages else _build_page_set()

    async def sync_one(key: str, site: dict) -> int:
        total = 0
        empty_streak = 0
        try:
            for pg in page_set:
                try:
                    lst = await _fetch_list(site["api"], pg)
                except Exception:  # noqa: BLE001 单页异常跳过，继续下一页
                    empty_streak += 1
                    if empty_streak >= _EMPTY_BREAK_STREAK:
                        break
                    continue
                if not lst:
                    # 空页：连续达到阈值则提前退出（源站到底/暂时无数据），停止空拉
                    empty_streak += 1
                    if empty_streak >= _EMPTY_BREAK_STREAK:
                        break
                    continue
                empty_streak = 0
                rows: list[dict] = []
                for it in lst:
                    if not isinstance(it, dict):
                        continue
                    rows.append(
                        {
                            "source": key,
                            "source_name": site["name"],
                            "vod_id": str(it.get("vod_id") or ""),
                            "title": str(it.get("vod_name") or ""),
                            "type_name": it.get("type_name"),
                            "pic": pic_local(it.get("vod_pic")),
                            "remarks": it.get("vod_remarks"),
                            "area": it.get("vod_area"),
                            "year": it.get("vod_year"),
                            "play_url": it.get("vod_play_url"),
                            "vod_time": it.get("vod_time"),
                        }
                    )
                if rows:
                    total += db.upsert_videos(rows)
        except Exception as exc:  # noqa: BLE001 单源整体失败，不影响其他源
            stats["failed"].append(key)
        return total

    results = await asyncio.gather(*[sync_one(k, v) for k, v in sources])
    stats["items"] = sum(results)
    # 清理 N 天未更新的残留，但保护「任意用户观看历史里出现过的内容」
    # （viewing_history.source 存的是源中文名，需映射回 key 与 videos.source 匹配）
    name_to_key = {site["name"]: key for key, site in SITES.items()}
    protected: set[tuple[str, str]] = set()
    for src_name, vod_id in db.get_history_source_vod_pairs():
        key = name_to_key.get(src_name, src_name)  # 映射不到就按原样兜底
        protected.add((key, str(vod_id)))
    stats["protected"] = len(protected)
    stats["cleaned"] = db.cleanup_videos(SYNC_STALE_DAYS, protected)
    return stats
