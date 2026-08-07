"""详情解析：/api/detail?id=&source=&customApi=&customDetail=

响应格式为扁平结构（兼容既有前端调用方）：
  {code: 200, episodes: [...], detailUrl: str, videoInfo: {...}}

解析逻辑移植自旧 js/api.js handleApiRequest 的详情分支：
- vod_play_url 用 $$$ 分播放源、# 分集数、$ 分"标题$URL"，取第一个源的 URL
- 无播放地址时用 M3U8 正则从 vod_content 兜底
- 自定义源（customApi）按标准 CMS JSON 处理；customDetail/useDetail 传 HTML 详情页
  场景暂不支持（当前内置源均为标准 CMS，无此需求）
"""

import re

import httpx
from fastapi import HTTPException

from .config import REQUEST_TIMEOUT, USER_AGENT
from .security import validate_target_url
from .sites import SITES

_client = httpx.AsyncClient(follow_redirects=True, timeout=REQUEST_TIMEOUT)

_DETAIL_PATH = "?ac=videolist&ids="
_M3U8_PATTERN = re.compile(r"\$https?://[^\"'\s]+?\.m3u8")


def _parse_episodes(vod_play_url: str | None, vod_content: str | None) -> list[str]:
    episodes: list[str] = []
    if vod_play_url:
        play_sources = vod_play_url.split("$$$")
        if play_sources:
            episode_list = play_sources[0].split("#")
            for ep in episode_list:
                parts = ep.split("$")
                url = parts[1] if len(parts) > 1 else ""
                if url.startswith(("http://", "https://")):
                    episodes.append(url)
    if not episodes and vod_content:
        episodes = [m.replace("$", "") for m in _M3U8_PATTERN.findall(vod_content)]
    return episodes


async def get_detail(
    id: str,
    source: str | None = None,
    customApi: str | None = None,
    customDetail: str | None = None,
    useDetail: str | None = None,
) -> dict:
    if not id:
        raise HTTPException(400, "缺少视频ID参数")
    if not re.fullmatch(r"[\w-]+", id):
        raise HTTPException(400, "无效的视频ID格式")

    if source == "custom":
        if not customApi:
            raise HTTPException(400, "使用自定义API时必须提供API地址")
        await validate_target_url(customApi)
        api_base, source_name, source_code = customApi, "自定义源", "custom"
    else:
        src_key = source or "jinying"
        site = SITES.get(src_key)
        if not site:
            raise HTTPException(400, "无效的API来源")
        api_base, source_name, source_code = site["api"], site["name"], src_key

    detail_url = api_base.rstrip("/") + _DETAIL_PATH + id
    await validate_target_url(detail_url)

    try:
        resp = await _client.get(
            detail_url,
            headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
        )
    except httpx.HTTPError as e:
        raise HTTPException(502, f"详情请求失败: {e}")
    if resp.status_code != 200:
        raise HTTPException(502, f"详情请求失败: {resp.status_code}")

    data = resp.json()
    lst = data.get("list") if isinstance(data, dict) else None
    if not lst or not isinstance(lst, list) or not lst:
        raise HTTPException(404, "未获取到视频详情")

    v = lst[0]
    episodes = _parse_episodes(v.get("vod_play_url"), v.get("vod_content"))
    return {
        "code": 200,
        "episodes": episodes,
        "detailUrl": detail_url,
        "videoInfo": {
            "title": v.get("vod_name"),
            "cover": v.get("vod_pic"),
            "desc": v.get("vod_content"),
            "type": v.get("type_name"),
            "year": v.get("vod_year"),
            "area": v.get("vod_area"),
            "director": v.get("vod_director"),
            "actor": v.get("vod_actor"),
            "remarks": v.get("vod_remarks"),
            "source_name": source_name,
            "source_code": source_code,
        },
    }
