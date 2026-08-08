"""首页数据源：/api/items

主路径：查 videos 镜像表（定时同步的最新内容），跨源去重后返回——毫秒级，不打资源站。
表为空（冷启动/同步未完成）时兜底：实时并发拉取前 4 源 × 页数，合并去重返回。

分类分组与 hero 选片保持在前端（discovery.js），便于前端灵活增删种类。

分批：/api/items 支持 offset/limit 分片返回。每批都基于「最新 TOTAL_LIMIT 条 → 全局去重」后的
切片，total 恒为全局去重后总数，前端直接按 offset 拼接不会重复：
首屏请求 offset=0&limit=500 先渲染，其余批次后台补齐（不影响首屏速度）。
"""

import asyncio

from . import db
from .sites import SITES
from .vodlist import _dedup, _fetch_list

HOME_SOURCE_COUNT = 4  # 首页使用的源数（与前端原 aggregateVodList slice(0,4) 一致）
HOME_PAGE_COUNT = 4  # 兜底实时拉取的页数（仅表为空时用）
HOME_TOTAL_LIMIT = 2000  # 镜像表取最新条数上限（去重前）——随库量增长取最新 2000 条覆盖首页+分类
HOME_BATCH_SIZE = 500  # 单批返回条数（前端首屏渲染与后台补齐的粒度）


async def get_home(offset: int = 0, limit: int = HOME_BATCH_SIZE) -> dict:
    # 主路径：查 videos 镜像表（毫秒级，零上游请求）
    rows = db.query_videos_latest(limit=HOME_TOTAL_LIMIT)
    if rows:
        items = _dedup(rows)
        total = len(items)
        return {
            "items": items[offset:offset + limit],
            "total": total,
            "offset": offset,
            "has_more": offset + limit < total,
        }

    # 兜底：表为空（冷启动同步还没跑完）→ 实时拉取（老行为，一次返回全量）
    sources = [(k, v) for k, v in SITES.items() if not v["adult"]][:HOME_SOURCE_COUNT]
    # (key, site) 按 源 × 页 展开，与 gather 的返回值一一对应
    key_site_pairs = [(k, v) for k, v in sources for _ in range(HOME_PAGE_COUNT)]
    lists = await asyncio.gather(
        *[
            _fetch_list(v["api"], pg)
            for _, v in sources
            for pg in range(1, HOME_PAGE_COUNT + 1)
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
    return {"items": items, "total": len(items), "offset": 0, "has_more": False}
