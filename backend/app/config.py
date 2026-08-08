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

# 资源镜像表同步（videos 表定时从资源站拉取）
SYNC_INTERVAL_HOURS = float(os.getenv("SYNC_INTERVAL_HOURS", "24"))  # 同步间隔（小时），默认每天 1 次
SYNC_PAGES_PER_SOURCE = int(os.getenv("SYNC_PAGES_PER_SOURCE", "10"))  # 每个源拉取页数（约 20-30 条/页）

# TTL 缓存（内存，服务搜索/详情等实时端点）
TTL_CACHE_MAX_ITEMS = int(os.getenv("TTL_CACHE_MAX_ITEMS", "200"))  # 缓存条数上限（LRU 淘汰）
SEARCH_TTL_SECONDS = int(os.getenv("SEARCH_TTL", "300"))  # 搜索结果缓存秒数（默认 5 分钟）
DETAIL_TTL_SECONDS = int(os.getenv("DETAIL_TTL", "1800"))  # 详情缓存秒数（默认 30 分钟）
