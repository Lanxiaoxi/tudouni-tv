"""资源镜像表定时同步：拉资源站列表写入 videos 表。

- 遍历所有普通源（排除 adult），每源拉 SYNC_PAGES_PER_SOURCE 页（首页即最新内容）
- 按 (source, vod_id) upsert：已存在更新、新出现插入
- 同步完成后清理 SYNC_STALE_DAYS 天未更新的残留行（保持「最新内容索引」语义）
- 单源失败跳过不影响其他源；源站挂掉时表里旧数据保留，服务仍可用
- 由 main.py 的 asyncio 后台任务按 SYNC_INTERVAL_HOURS 间隔调用
"""

import asyncio

from . import db
from .config import SYNC_PAGES_PER_SOURCE, SYNC_STALE_DAYS
from .sites import SITES
from .vodlist import _fetch_list


async def sync_all_sources(pages: int | None = None) -> dict:
    """同步全部普通源到 videos 表，并清理过期残留。返回统计信息 {sources, items, failed, cleaned}。"""
    pages = pages or SYNC_PAGES_PER_SOURCE
    sources = [(k, v) for k, v in SITES.items() if not v["adult"]]
    stats: dict = {"sources": len(sources), "items": 0, "failed": [], "cleaned": 0}

    async def sync_one(key: str, site: dict) -> int:
        total = 0
        try:
            for pg in range(1, pages + 1):
                lst = await _fetch_list(site["api"], pg)
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
                            "pic": it.get("vod_pic"),
                            "remarks": it.get("vod_remarks"),
                            "area": it.get("vod_area"),
                            "year": it.get("vod_year"),
                            "play_url": it.get("vod_play_url"),
                        }
                    )
                if rows:
                    total += db.upsert_videos(rows)
        except Exception as exc:  # noqa: BLE001 单源失败跳过
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
    stats["cleaned"] = db.cleanup_videos(SYNC_STALE_DAYS, protected)
    stats["protected"] = len(protected)
    return stats
