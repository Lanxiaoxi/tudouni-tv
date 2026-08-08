"""LibreTV API 入口。

启动：uvicorn app.main:app --host 127.0.0.1 --port 9797
SERVE_STATIC=true（默认）时挂载前端目录，一键跑通全站（开发模式）。
生产环境由 Nginx 托管前端并反代 /api/*，可设 SERVE_STATIC=false。
"""

import logging
import mimetypes
import time

from fastapi import Depends, FastAPI, Header, Query, Request
from fastapi.exceptions import HTTPException as FastAPIHTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from fastapi.staticfiles import StaticFiles

from . import auth, db, detail, home, proxy, search, userdata, vodlist

# 修复 Windows 上 Python mimetypes 把 .js 映射成 text/plain 的问题
# （浏览器会拒绝执行 text/plain 的 <script>，导致前端 JS 全部不生效）
mimetypes.add_type("application/javascript", ".js")
mimetypes.add_type("text/javascript", ".mjs")
from .config import FRONTEND_DIR, SERVE_STATIC

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("libretv")

app = FastAPI(title="LibreTV API", version="0.1.0")

# 初始化 SQLite（幂等）
db.init_db()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def access_log(request: Request, call_next):
    start = time.time()
    response = await call_next(request)
    logger.info(
        "%s %s -> %s (%.0fms)",
        request.method, request.url.path, response.status_code,
        (time.time() - start) * 1000,
    )
    return response


@app.exception_handler(FastAPIHTTPException)
async def http_exc_handler(request: Request, exc: FastAPIHTTPException):
    return JSONResponse(
        status_code=exc.status_code,
        content={"code": exc.status_code, "message": str(exc.detail)},
    )


@app.exception_handler(Exception)
async def unhandled_exc_handler(request: Request, exc: Exception):
    logger.exception("未处理异常: %s %s", request.method, request.url.path)
    return JSONResponse(status_code=500, content={"code": 500, "message": "服务器内部错误"})


@app.get("/api/health")
async def health():
    return {"code": 0, "data": {"status": "ok"}, "message": "ok"}


@app.post("/api/auth/register")
async def api_register(body: dict):
    username = (body or {}).get("username")
    password = (body or {}).get("password")
    if not isinstance(username, str) or not isinstance(password, str):
        raise FastAPIHTTPException(400, "缺少用户名或密码")
    result = await auth.register(username, password)
    return {"code": 0, "data": result, "message": "ok"}


@app.post("/api/auth/login")
async def api_login(body: dict):
    username = (body or {}).get("username")
    password = (body or {}).get("password")
    if not isinstance(username, str) or not isinstance(password, str):
        raise FastAPIHTTPException(400, "缺少用户名或密码")
    result = await auth.login(username, password)
    return {"code": 0, "data": result, "message": "ok"}


@app.post("/api/auth/logout")
async def api_logout(authorization: str | None = Header(default=None)):
    token = authorization[7:].strip() if authorization and authorization.startswith("Bearer ") else ""
    if token:
        await auth.logout(token)
    return {"code": 0, "data": {"ok": True}, "message": "ok"}


# ---------- 用户数据（按 user_id 隔离） ----------

@app.get("/api/me")
async def api_me(user_id: int = Depends(auth.require_token)):
    data = await userdata.get_me(user_id)
    return {"code": 0, "data": data, "message": "ok"}


@app.put("/api/me/settings")
async def api_put_settings(body: dict, user_id: int = Depends(auth.require_token)):
    data = await userdata.put_settings(body, user_id)
    return {"code": 0, "data": data, "message": "ok"}


@app.get("/api/history")
async def api_history(limit: int = Query(50, ge=1, le=200), user_id: int = Depends(auth.require_token)):
    data = await userdata.get_history(limit, user_id)
    return {"code": 0, "data": data, "message": "ok"}


@app.put("/api/history")
async def api_put_history(body: dict, user_id: int = Depends(auth.require_token)):
    data = await userdata.put_history(body, user_id)
    return {"code": 0, "data": data, "message": "ok"}


@app.delete("/api/history")
async def api_delete_history(user_id: int = Depends(auth.require_token)):
    data = await userdata.delete_history(user_id)
    return {"code": 0, "data": data, "message": "ok"}


@app.delete("/api/history/item")
async def api_delete_history_item(
    vod_id: str | None = None,
    source: str | None = None,
    title: str | None = None,
    user_id: int = Depends(auth.require_token),
):
    data = await userdata.delete_history_item(vod_id, source, title, user_id)
    return {"code": 0, "data": data, "message": "ok"}


@app.get("/api/search-history")
async def api_search_history(limit: int = Query(50, ge=1, le=200), user_id: int = Depends(auth.require_token)):
    data = await userdata.get_search_history(limit, user_id)
    return {"code": 0, "data": data, "message": "ok"}


@app.post("/api/search-history")
async def api_post_search_history(body: dict, user_id: int = Depends(auth.require_token)):
    data = await userdata.post_search_history(body, user_id)
    return {"code": 0, "data": data, "message": "ok"}


@app.delete("/api/search-history")
async def api_delete_search_history(user_id: int = Depends(auth.require_token)):
    data = await userdata.delete_search_history(user_id)
    return {"code": 0, "data": data, "message": "ok"}


@app.delete("/api/search-history/item")
async def api_delete_search_history_item(
    keyword: str = None,
    user_id: int = Depends(auth.require_token),
):
    data = await userdata.delete_search_history_item(keyword, user_id)
    return {"code": 0, "data": data, "message": "ok"}


@app.get("/api/search")
async def api_search(
    wd: str = Query(...),
    source: str | None = None,
    api_url: str | None = None,
    page: int = Query(1, ge=1),
    _: None = Depends(auth.require_token),
):
    data = await search.aggregated_search(wd, source, api_url, page)
    return {"code": 0, "data": data, "message": "ok"}


@app.get("/api/vodlist")
async def api_vodlist(
    cat: str | None = None,
    source: str | None = None,
    api_url: str | None = None,
    pg: int = Query(1, ge=1),
    t: str | None = None,
    h: str | None = None,
    by: str | None = None,
    order: str | None = None,
    zy: str | None = None,
    year: str | None = None,
    area: str | None = None,
    lang: str | None = None,
    _: None = Depends(auth.require_token),
):
    data = await vodlist.get_vodlist(
        cat, source, api_url, pg,
        t=t, h=h, by=by, order=order, zy=zy, year=year, area=area, lang=lang,
    )
    return {"code": 0, "data": data, "message": "ok"}


@app.get("/api/items")
async def api_items(
    pg: int = Query(4, ge=1, le=10),
    _: None = Depends(auth.require_token),
):
    """数据项：后端完成拉取/去重，一次返回全量 items（首页与分类页共享同一份数据）。"""
    data = await home.get_home(pg)
    return {"code": 0, "data": data, "message": "ok"}


@app.get("/api/proxy")
async def api_proxy(
    url: str = Query(...),
    _: None = Depends(auth.require_token),
) -> Response:
    # 通用代理返回原始内容（JSON / 图片等），不包裹统一格式
    return await proxy.proxy_url(url)


@app.get("/api/detail")
async def api_detail(
    id: str = Query(...),
    source: str | None = None,
    customApi: str | None = None,
    customDetail: str | None = None,
    useDetail: str | None = None,
    _: None = Depends(auth.require_token),
):
    # 兼容既有前端调用方：返回扁平 {code, episodes, detailUrl, videoInfo}
    return await detail.get_detail(id, source, customApi, customDetail, useDetail)


# 开发模式：挂载前端静态目录（放在 API 路由之后注册，避免抢占 /api/*）
if SERVE_STATIC and FRONTEND_DIR.exists():
    app.mount("/", StaticFiles(directory=str(FRONTEND_DIR), html=True), name="static")
