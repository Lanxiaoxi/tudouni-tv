"""软件更新（TV 客户端「检查更新」用，无需鉴权）。

- GET /api/app/version  版本信息（读取 backend/app_version.json）
- GET /api/app/download  返回 APK 安装包（backend/tudounitv.apk）

发版流程：更新 backend/app_version.json（latest_version / latest_code）+ 上传新 APK 到
backend/tudounitv.apk，无需改代码重启（version 路由每次实时读文件）。
"""

import json
import logging
from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse, JSONResponse

logger = logging.getLogger("libretv")

# backend/ 目录（本文件位于 backend/app/update.py）
_BACKEND_DIR = Path(__file__).resolve().parent.parent

VERSION_FILE = _BACKEND_DIR / "app_version.json"
APK_FILE = _BACKEND_DIR / "tudounitv.apk"

router = APIRouter(prefix="/api/app", tags=["update"])


@router.get("/version")
async def app_version():
    try:
        data = json.loads(VERSION_FILE.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        logger.error("读取版本信息失败: %s", exc)
        return JSONResponse(status_code=500, content={"code": 500, "message": "版本信息缺失"})
    if not all(k in data for k in ("latest_version", "latest_code", "download_url")):
        return JSONResponse(status_code=500, content={"code": 500, "message": "版本信息格式错误"})
    return {"code": 0, "data": data, "message": "ok"}


@router.get("/download")
async def app_download():
    if not APK_FILE.exists():
        return JSONResponse(status_code=404, content={"code": 404, "message": "安装包不存在"})
    return FileResponse(
        APK_FILE,
        media_type="application/vnd.android.package-archive",
        filename=APK_FILE.name,
    )
