"""腾讯视频热播榜单：/api/hotrank/tencent

实现（平行于爱奇艺热播，见 hotrank.py 通用框架）：
- POST https://pbaccess.video.qq.com/trpc.vector_layout.page_view.PageService/getPage
  频道数据接口（无需签名/cookie，普通浏览器 UA 裸 POST 即 200）
- 解析：data.CardList[].children_list.list 递归收集 title（卡片式嵌套结构）
- 播放数据走现有资源站聚合搜索（hotrank.HotRankSource），与普通搜索链路一致

注意：榜单页专用 page_id 尚未定位（旧 v.qq.com/channel/rank 已 302，旧 RankList 接口已失效），
当前使用频道页 page_id=100101（返回热播内容），待定位榜单 page_id 后替换即可。
参考：优酷腾讯热播榜数据获取.md
"""

import json
import logging

import httpx

from .config import REQUEST_TIMEOUT, USER_AGENT
from .hotrank import HotRankSource

logger = logging.getLogger("libretv")

_TENCENT_PAGE_URL = (
    "https://pbaccess.video.qq.com/trpc.vector_layout.page_view.PageService/getPage"
    "?video_appid=3000010"
)
# 频道页 page_id（榜单页 page_id 待定位，见模块 docstring）
_TENCENT_PAGE_ID = "100101"
_TOP_N = 20

_client = httpx.AsyncClient(
    follow_redirects=True,
    timeout=REQUEST_TIMEOUT,
    headers={"User-Agent": USER_AGENT, "Content-Type": "application/json"},
)


def _build_payload() -> dict:
    return {
        "page_context": None,
        "page_params": {
            "page_id": _TENCENT_PAGE_ID,
            "page_type": "channel",
        },
        "page_bypass_params": {
            "params": {
                "caller_id": "3000010",
                "data_mode": "default",
                "page_id": _TENCENT_PAGE_ID,
                "page_type": "channel",
                "platform_id": "2",
                "user_mode": "default",
            },
            "scene": "channel",
        },
    }


def _collect_titles(node, titles: list[str], seen: set[str]) -> None:
    """递归收集所有 title 字段（去重保序），深度优先。"""
    if isinstance(node, dict):
        t = node.get("title")
        if isinstance(t, str) and t.strip() and t not in seen:
            seen.add(t)
            titles.append(t.strip())
        for v in node.values():
            _collect_titles(v, titles, seen)
    elif isinstance(node, list):
        for v in node:
            _collect_titles(v, titles, seen)


async def fetch_titles() -> list[str]:
    """POST 频道接口，递归提取片名列表。失败抛异常由调用方降级。"""
    resp = await _client.post(_TENCENT_PAGE_URL, json=_build_payload())
    resp.raise_for_status()
    data = resp.json()

    titles: list[str] = []
    seen: set[str] = set()
    _collect_titles(data.get("data") or {}, titles, seen)
    return titles[: _TOP_N]


# 榜单源实例（main.py 导入使用）
tencent = HotRankSource(
    name="tencent",
    cache_key="hotrank:tencent",
    fetch_titles=fetch_titles,
    top_n=_TOP_N,
)
