"""LibreTV 后端配置。所有可调项均支持环境变量覆盖。"""

import os
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent   # backend/
FRONTEND_DIR = BACKEND_DIR.parent                      # LibreTV/ 前端根目录

# 安全
PASSWORD = os.getenv("PASSWORD", "")                       # 必填：登录密码
TOKEN_TTL_SECONDS = int(os.getenv("TOKEN_TTL_DAYS", "7")) * 86400

# 服务
HOST = os.getenv("HOST", "127.0.0.1")
PORT = int(os.getenv("PORT", "9797"))
SERVE_STATIC = os.getenv("SERVE_STATIC", "true").lower() in ("1", "true", "yes", "on")

# 代理
REQUEST_TIMEOUT = float(os.getenv("REQUEST_TIMEOUT", "8"))
MAX_RETRIES = int(os.getenv("MAX_RETRIES", "2"))
CACHE_TTL_SECONDS = int(os.getenv("CACHE_TTL", "600"))
MAX_CACHE_BYTES = int(os.getenv("MAX_CACHE_BYTES", str(5 * 1024 * 1024)))  # 超过不缓存
USER_AGENT = os.getenv(
    "USER_AGENT",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
)

# 搜索
MAX_QUERY_LENGTH = int(os.getenv("MAX_QUERY_LENGTH", "100"))
