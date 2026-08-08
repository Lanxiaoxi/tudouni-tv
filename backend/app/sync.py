"""资源镜像表定时同步：拉资源站列表写入 videos 表。

- 遍历所有普通源（排除 adult），每源拉 SYNC_PAGES_PER_SOURCE 页（首页即最新内容）
- 按 (source, vod_id) upsert：已存在更新、新出现插入
- 单源失败跳过不影响其他源；源站挂掉时表里旧数据保留，服务仍可用
- 由 main.py 的 asyncio 后台任务按 SYNC_INTERVAL_HOURS 间隔调用
"""

import asyncio

from . import db
from .config import SYNC_PAGES_PER_SOURCE
from .sites import SITES
from .vodlist import _fetch_list


async def sync_all_sources(pages: int | None = None) -> dict:
    """同步全部普通源到 videos 表。返回统计信息 {sources, items, failed}。"""
    pages = pages or SYNC_PAGES_PER_SOURCE
    sources = [(k, v) for k, v in SITES.items() if not v["adult"]]
    stats: dict = {"sources": len(sources), "items": 0, "failed": []}

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
    return stats
