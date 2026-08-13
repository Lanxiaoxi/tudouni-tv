"""爱奇艺热播榜单：/api/iqiyi/hot

实现（HotRankSource 通用框架，见 hotrank.py）：
- 爱奇艺只提供**片名**（热播榜 tag=0，无 m3u8），实际播放数据走现有资源站聚合搜索
  （search.aggregated_search 按片名搜，命中即得带 m3u8 的完整条目）——链路与普通搜索一致
- 缓存 + 数据库条目并集兜底：内存缓存 5 天（后台预热），刷新成功条目合并写 SQLite，
  拉取失败时从数据库取最近快照（hotrank.HotRankSource 内置）
- 限流：asyncio.Semaphore，避免 20 片名并发全源搜索打爆资源站
- 失败降级：任何异常返回空列表（或数据库兜底），绝不阻塞首页其他内容行
"""

import logging

import httpx

from .config import REQUEST_TIMEOUT, USER_AGENT
from .hotrank import HotRankSource

logger = logging.getLogger("libretv")

# 爱奇艺榜单接口与参数（见文档 topRanksData）
_IQIYI_RANK_URL = "https://pcw-api.iqiyi.com/strategy/pcw/data/topRanksData"
_IQIYI_TAG = 0          # 热播榜
_IQIYI_CATEGORY_ID = 1  # 全站
_TOP_N = 20

_client = httpx.AsyncClient(
    follow_redirects=True,
    timeout=REQUEST_TIMEOUT,
    headers={"User-Agent": USER_AGENT},
)


async def fetch_titles() -> list[str]:
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
        if len(titles) >= _TOP_N:
            break
    return titles


# 榜单源实例（main.py 导入使用）；缓存 key 沿用旧值 "iqiyi:hot"
iqiyi = HotRankSource(
    name="iqiyi",
    cache_key="iqiyi:hot",
    fetch_titles=fetch_titles,
    top_n=_TOP_N,
)
