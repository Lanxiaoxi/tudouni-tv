"""优酷热播榜单：/api/hotrank/youku

实现（平行于爱奇艺热播，见 hotrank.py 通用框架）：
- GET https://v.youku.com/ranking/ 榜单页，数据内嵌于 HTML 的 window.__INITIAL_DATA__
  （服务端渲染直出，无需 mtop 网关/签名）
- 解析：括号平衡截取 JSON → 把 JS 裸 undefined 修正为 null → json.loads
- 片名提取：遍历 moduleList[].components[].itemList[].title（多个榜单模块，去重保序）
- 播放数据走现有资源站聚合搜索（hotrank.HotRankSource），与普通搜索链路一致

抓取失败降级为空列表（首页该行隐藏），不阻塞主业务。参考：优酷腾讯热播榜数据获取.md
"""

import json
import logging
import re

import httpx

from .config import REQUEST_TIMEOUT, USER_AGENT
from .hotrank import HotRankSource

logger = logging.getLogger("libretv")

_YOUKU_RANK_URL = "https://v.youku.com/ranking/"
_TOP_N = 20

_client = httpx.AsyncClient(
    follow_redirects=True,
    timeout=REQUEST_TIMEOUT,
    headers={"User-Agent": USER_AGENT},
)


def _extract_json_balanced(text: str, start: int) -> str:
    """从 start（JSON 起点）做括号平衡截取，跳过字符串与转义，返回完整 JSON 文本。"""
    depth = 0
    in_str = False
    esc = False
    for i in range(start, len(text)):
        c = text[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        else:
            if c == '"':
                in_str = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return text[start : i + 1]
    raise ValueError("__INITIAL_DATA__ JSON 未闭合")


async def fetch_titles() -> list[str]:
    """拉取优酷榜单页，解析内嵌 JSON，返回去重后的片名列表。失败抛异常由调用方降级。"""
    resp = await _client.get(_YOUKU_RANK_URL)
    resp.raise_for_status()
    html = resp.text

    m = re.search(r"window\.__INITIAL_DATA__\s*=\s*", html)
    if not m:
        raise ValueError("优酷榜单页未找到 __INITIAL_DATA__")

    raw = _extract_json_balanced(html, m.end())
    # JS 内嵌 JSON 含裸 undefined（非标准 JSON），修正为 null 后解析
    fixed = (
        raw.replace(":undefined", ":null")
        .replace(",undefined", ",null")
        .replace("[undefined", "[null")
        .replace("{undefined", "{null")
    )
    data = json.loads(fixed)

    titles: list[str] = []
    seen: set[str] = set()
    for mod in data.get("moduleList", []):
        for comp in mod.get("components", []):
            for item in comp.get("itemList", []):
                title = (item.get("title") or "").strip()
                if title and title not in seen:
                    seen.add(title)
                    titles.append(title)
    return titles[: _TOP_N]


# 榜单源实例（main.py 导入使用）
youku = HotRankSource(
    name="youku",
    cache_key="hotrank:youku",
    fetch_titles=fetch_titles,
    top_n=_TOP_N,
)
