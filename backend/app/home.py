"""首页数据源：/api/items

主路径：查 videos 镜像表（定时同步的最新内容），跨源去重后返回——毫秒级，不打资源站。
表为空（冷启动/同步未完成）时兜底：实时并发拉取前 4 源 × 页数，合并去重返回。

分类分组与 hero 选片保持在前端（discovery.js），便于前端灵活增删种类。
"""

import asyncio

from . import db
from .sites import SITES
from .vodlist import _dedup, _fetch_list

HOME_SOURCE_COUNT = 4  # 首页使用的源数（与前端原 aggregateVodList slice(0,4) 一致）
HOME_PAGE_COUNT = 4  # 默认拉取页数（首页/分类页共享一份数据，深度覆盖低频分类）
HOME_ITEMS_LIMIT = 500  # 查镜像表取最新条数（去重前，覆盖各分类行 + hero）


async def get_home(pg_count: int = HOME_PAGE_COUNT) -> dict:
    # 主路径：查 videos 镜像表（毫秒级，零上游请求）
    rows = db.query_videos_latest(limit=HOME_ITEMS_LIMIT)
    if rows:
        items = _dedup(rows)
        return {"items": items, "total": len(items)}

    # 兜底：表为空（冷启动同步还没跑完）→ 实时拉取（老行为）
    sources = [(k, v) for k, v in SITES.items() if not v["adult"]][:HOME_SOURCE_COUNT]
    # (key, site) 按 源 × 页 展开，与 gather 的返回值一一对应
    key_site_pairs = [(k, v) for k, v in sources for _ in range(pg_count)]
    lists = await asyncio.gather(
        *[
            _fetch_list(v["api"], pg)
            for _, v in sources
            for pg in range(1, pg_count + 1)
        ]
    )
    items: list[dict] = []
    for (key, site), lst in zip(key_site_pairs, lists):
        for it in lst:
            if isinstance(it, dict):
                it["source_name"] = site["name"]
                it["source_code"] = key
                items.append(it)
    items = _dedup(items)
    return {"items": items, "total": len(items)}
