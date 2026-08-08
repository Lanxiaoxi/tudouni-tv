"""用户数据端点：/api/me、/api/me/settings、/api/history、/api/search-history。

所有端点按 user_id 隔离（require_token 依赖注入）。读取类聚合端点
（/api/items、/api/search 等）不在此文件，仍保持只读透传。
"""

import json

from fastapi import Depends, HTTPException

from . import db
from .auth import require_token


async def get_me(user_id: int = Depends(require_token)) -> dict:
    user = db.get_user(user_id)
    if user is None:
        raise HTTPException(404, "用户不存在")
    try:
        settings = json.loads(user["settings"] or "{}")
    except ValueError:
        settings = {}
    return {
        "id": user["id"],
        "username": user["username"],
        "created_at": user["created_at"],
        "settings": settings,
    }


async def put_settings(body: dict, user_id: int = Depends(require_token)) -> dict:
    if not isinstance(body, dict):
        raise HTTPException(400, "请求体需为 JSON 对象")
    # 只接受白名单键，避免前端意外写入任意字段
    allowed = {"selectedAPIs", "customAPIs", "appTheme", "autoplayEnabled",
               "yellowFilterEnabled", "adFilteringEnabled", "doubanEnabled",
               "userMovieTags", "userTvTags"}
    clean = {k: v for k, v in body.items() if k in allowed}
    user = db.get_user(user_id)
    if user is None:
        raise HTTPException(404, "用户不存在")
    try:
        current = json.loads(user["settings"] or "{}")
    except ValueError:
        current = {}
    current.update(clean)

    conn = db.get_conn()
    try:
        conn.execute(
            "UPDATE users SET settings = ? WHERE id = ?",
            (json.dumps(current, ensure_ascii=False), user_id),
        )
        conn.commit()
    finally:
        conn.close()
    return {"settings": current}


# ---------- 观看历史 ----------

async def get_history(limit: int = 50, user_id: int = Depends(require_token)) -> dict:
    items = db.get_history(user_id, limit=min(max(limit, 1), 200))
    return {"items": items, "total": len(items)}


async def put_history(body: dict, user_id: int = Depends(require_token)) -> dict:
    if not isinstance(body, dict) or not body.get("title"):
        raise HTTPException(400, "缺少标题或请求体格式错误")
    db.upsert_history(user_id, body)
    return {"ok": True}


async def delete_history(user_id: int = Depends(require_token)) -> dict:
    db.clear_history(user_id)
    return {"ok": True}


async def delete_history_item(
    vod_id: str | None = None,
    source: str | None = None,
    title: str | None = None,
    user_id: int = Depends(require_token),
) -> dict:
    if not (vod_id or title):
        raise HTTPException(400, "缺少 vod_id 或 title 参数")
    removed = db.delete_history_item(user_id, vod_id, source, title)
    return {"ok": True, "removed": removed}


# ---------- 搜索历史 ----------

async def get_search_history(limit: int = 50, user_id: int = Depends(require_token)) -> dict:
    items = db.get_search_history(user_id, limit=min(max(limit, 1), 200))
    return {"items": items, "total": len(items)}


async def post_search_history(body: dict, user_id: int = Depends(require_token)) -> dict:
    keyword = (body or {}).get("keyword")
    if not keyword or not str(keyword).strip():
        raise HTTPException(400, "缺少搜索关键词")
    db.add_search_history(user_id, str(keyword).strip()[:100])
    return {"ok": True}


async def delete_search_history(user_id: int = Depends(require_token)) -> dict:
    db.clear_search_history(user_id)
    return {"ok": True}


async def delete_search_history_item(
    keyword: str = None,
    user_id: int = Depends(require_token),
) -> dict:
    if not keyword or not str(keyword).strip():
        raise HTTPException(400, "缺少 keyword 参数")
    removed = db.delete_search_history_item(user_id, str(keyword).strip())
    return {"ok": True, "removed": removed}
