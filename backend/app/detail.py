"""详情解析：/api/detail?id=&source=&customApi=&customDetail=

响应格式为扁平结构（兼容既有前端调用方）：
  {code: 200, episodes: [...], detailUrl: str, videoInfo: {...}}

解析逻辑移植自旧 js/api.js handleApiRequest 的详情分支：
- vod_play_url 用 $$$ 分播放源、# 分集数、$ 分"标题$URL"，取第一个源的 URL
- 无播放地址时用 M3U8 正则从 vod_content 兜底
- 地址规整：资源站常返回播放页地址（/play/xxx，非直链 m3u8），统一在此补
  为 /play/xxx/index.m3u8（移植自 js/pages/player.js normalizePlayUrl）——
  之前只有 Web 端播放器侧做了这层处理，TV 端（Media3 ExoPlayer）直接拿原始
  地址播放，缺这一步；现在改为后端统一吐出规整后的地址，两端行为一致
- 自定义源（customApi）按标准 CMS JSON 处理；customDetail/useDetail 传 HTML 详情页
  场景暂不支持（当前内置源均为标准 CMS，无此需求）

三级链路（与 search.py 一致，减少对资源站的实时请求）：
① TTL 缓存：同 id 短期内命中直接返回
② videos 镜像表：本地按 (source, vod_id) 精确查，play_url 非空才算命中
   （实测列表接口与详情接口返回的 vod_play_url 一致，可直接复用；如遇某源列表
   接口裁剪播放地址，本地记录 play_url 为空，则自然落到③兜底，不会返回空集数）
③ 实时兜底：①②都未命中才打资源站详情接口，解析后写回缓存 + 镜像表
   （命中过一次后，下次同一 id 可直接从②本地命中，不再打资源站）
- 自定义源（source=custom）不缓存、不查本地表，始终走③直连
"""

import re

import httpx
from fastapi import HTTPException

from . import db
from .cache import cache_get, cache_set
from .config import DETAIL_TTL_SECONDS, REQUEST_TIMEOUT, USER_AGENT
from .security import validate_target_url
from .sites import SITES
from .textutil import normalize_remarks

_client = httpx.AsyncClient(follow_redirects=True, timeout=REQUEST_TIMEOUT)

_DETAIL_PATH = "?ac=videolist&ids="
_M3U8_PATTERN = re.compile(r"\$https?://[^\"'\s]+?\.m3u8")

# 已是媒体扩展名的地址直接放行；播放页地址（/play/xxx）补 index.m3u8
# 移植自 js/pages/player.js normalizePlayUrl —— 与 Web 端保持一致，
# 使 TV 端（无客户端侧规整）也能拿到可直接播放的地址
_MEDIA_EXT_PATTERN = re.compile(r"\.(m3u8|mp4|webm|flv|m4v|m4s)([?#]|$)", re.IGNORECASE)
_PLAY_PAGE_PATTERN = re.compile(r"/play/[^?#]*/?$", re.IGNORECASE)


def _normalize_play_url(url: str) -> str:
    """资源站播放页地址（/play/xxx）→ 真实 m3u8（/play/xxx/index.m3u8）。

    已是媒体扩展名（m3u8/mp4 等）的地址原样返回；两条规则均不匹配时也原样返回
    （交给客户端播放器兜底，不确定处理反而可能破坏本就可用的地址）。
    """
    u = url.strip()
    if _MEDIA_EXT_PATTERN.search(u):
        return u
    if _PLAY_PAGE_PATTERN.search(u):
        return u.rstrip("/") + "/index.m3u8"
    return u


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
                    episodes.append(_normalize_play_url(url))
    if not episodes and vod_content:
        episodes = [_normalize_play_url(m.replace("$", "")) for m in _M3U8_PATTERN.findall(vod_content)]
    return episodes


def _build_result(v: dict, detail_url: str, source_name: str, source_code: str) -> dict:
    """把 CMS 原始字段（或本地镜像的 vod_* 兼容字段）组装成统一响应结构。"""
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
            "remarks": normalize_remarks(v.get("vod_remarks")),
            "source_name": source_name,
            "source_code": source_code,
        },
    }


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

    is_custom = source == "custom"
    cache_key = f"detail:{source or 'jinying'}:{id}"

    if not is_custom:
        # 一级：TTL 缓存命中直接返回
        cached = cache_get(cache_key)
        if cached is not None:
            return cached

        # 二级：本地 videos 镜像表精确查（play_url 非空才算命中）
        src_key = source or "jinying"
        local = db.get_video_by_id(src_key, id)
        if local is not None:
            detail_url = _DETAIL_PATH + id  # 未实际请求资源站，仅记录查询标识
            result = _build_result(local, detail_url, local["source_name"], src_key)
            if result["episodes"]:
                cache_set(cache_key, result, DETAIL_TTL_SECONDS)
                return result
            # 本地记录解析不出集数（如该源列表接口未带完整播放地址）→ 落到三级兜底

    # 三级：实时兜底（或自定义源直通）
    if is_custom:
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

    try:
        data = resp.json()
    except ValueError:
        raise HTTPException(502, "上游返回的数据格式异常")
    lst = data.get("list") if isinstance(data, dict) else None
    if not lst or not isinstance(lst, list) or not lst:
        raise HTTPException(404, "未获取到视频详情")

    v = lst[0]
    result = _build_result(v, detail_url, source_name, source_code)
    if not is_custom:
        cache_set(cache_key, result, DETAIL_TTL_SECONDS)
        # 按需填充：兜底命中结果写回镜像表，下次同一 id 可直接本地命中（②）
        try:
            db.upsert_videos([
                {
                    "source": source_code,
                    "source_name": source_name,
                    "vod_id": id,
                    "title": str(v.get("vod_name") or ""),
                    "type_name": v.get("type_name"),
                    "pic": v.get("vod_pic"),
                    "remarks": v.get("vod_remarks"),
                    "area": v.get("vod_area"),
                    "year": v.get("vod_year"),
                    "play_url": v.get("vod_play_url"),
                    "content": v.get("vod_content"),
                    "director": v.get("vod_director"),
                    "actor": v.get("vod_actor"),
                    "vod_time": v.get("vod_time"),
                }
            ])
        except Exception:
            pass  # 入库失败不影响详情返回
    return result
