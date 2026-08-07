"""首页数据源：/api/home

后端只做"拉取 + 去重"（无状态通用，不感知分类/选片业务）：
一次并发拉取前 4 源 × 2 页，合并、去重，返回全量 items。
分类分组与 hero 选片保持在前端（discovery.js），便于前端灵活增删种类。
"""

import asyncio

from .sites import SITES
from .vodlist import _dedup, _fetch_list

HOME_SOURCE_COUNT = 4  # 首页使用的源数（与前端原 aggregateVodList slice(0,4) 一致）
HOME_PAGE_COUNT = 2  # 首页拉取页数（覆盖低频分类命中率）


async def get_home() -> dict:
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
    return {"items": items, "total": len(items)}
