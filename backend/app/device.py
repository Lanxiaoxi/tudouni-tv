"""扫码登录 TV 端（设备码授权 / Device Code Grant）。

流程（详见 docs/tv-qr-login-design.md）：

- POST /api/auth/device/start    TV 未登录时调用，拿到 device_code + user_code（无需鉴权）
- POST /api/auth/device/poll     TV 轮询；confirmed 时一次性领取 token（无需鉴权）
- POST /api/auth/device/confirm  手机端确认授权（需 Bearer token，即手机自己的登录态）
- POST /api/auth/device/deny     手机端拒绝授权（需 Bearer token）

安全要点：

1. **两条码分工**：device_code 只给 TV（轮询凭据，不上屏、不进二维码）；
   user_code 给人看（二维码 + 人读）。拍到二维码的人只能「发起确认」，拿不到 token——
   token 只在 TV 轮询命中 confirmed 时签发。
2. **一次性**：confirmed --(poll 领取)--> consumed，靠 UPDATE ... WHERE status='confirmed'
   的原子写法防并发重复领取与重放。
3. **防爆破**：猜错的 user_code 在库里不存在，行上无从计数，
   故限流按调用方 IP 做（见 _rate_limit_ok）。
"""

import logging
import time

from fastapi import HTTPException, Request

from . import db
from .config import (
    DEVICE_CODE_TTL_SECONDS,
    DEVICE_CONFIRM_MAX_ATTEMPTS,
    DEVICE_POLL_INTERVAL_SECONDS,
    DEVICE_VERIFY_BASE_URL,
    TOKEN_TTL_SECONDS,
)

logger = logging.getLogger("libretv")

# 确认页路径。用 /tv.html 而非 /tv：Nginx 示例配置是 `try_files $uri $uri/ /index.html`，
# 不存在的无扩展名路径会回退到 index.html（首页），而 .html 文件能直接被命中。
VERIFY_PATH = "/tv.html"

# ---------- 防爆破：按 IP 的滑动窗口限流 ----------

_confirm_hits: dict[str, list[float]] = {}
_RATE_WINDOW_SECONDS = 60.0
_RATE_MAP_MAX_KEYS = 10_000  # 兜底上限，防止异常流量把字典撑大


def _client_ip(request: Request) -> str:
    """取真实客户端 IP：反代（Nginx）后面必须看 X-Forwarded-For 的第一段。"""
    fwd = request.headers.get("x-forwarded-for")
    if fwd:
        first = fwd.split(",")[0].strip()
        if first:
            return first
    return request.client.host if request.client else "unknown"


def _rate_limit_ok(ip: str) -> bool:
    """滑动窗口计数：同一 IP 在 60 秒内最多 DEVICE_CONFIRM_MAX_ATTEMPTS 次 confirm。"""
    now = time.time()
    if len(_confirm_hits) > _RATE_MAP_MAX_KEYS:
        # 极端流量兜底：丢掉已过期的键，避免字典无限增长
        for key in [k for k, v in _confirm_hits.items() if not v or now - v[-1] > _RATE_WINDOW_SECONDS]:
            _confirm_hits.pop(key, None)
    hits = [t for t in (_confirm_hits.get(ip) or []) if now - t < _RATE_WINDOW_SECONDS]
    if len(hits) >= DEVICE_CONFIRM_MAX_ATTEMPTS:
        _confirm_hits[ip] = hits
        return False
    hits.append(now)
    _confirm_hits[ip] = hits
    return True


# ---------- 二维码地址 ----------

def _verify_base(request: Request) -> str:
    """推导确认页的绝对地址前缀（二维码内容用它拼）。

    优先用 DEVICE_VERIFY_BASE_URL（显式配置最可靠）；否则按请求头推导——
    反代后真实协议在 X-Forwarded-Proto、真实域名可能在 X-Forwarded-Host，
    request.url 里的 scheme 往往是内网的 http。
    """
    if DEVICE_VERIFY_BASE_URL:
        return DEVICE_VERIFY_BASE_URL
    proto = (request.headers.get("x-forwarded-proto") or request.url.scheme or "http")
    proto = proto.split(",")[0].strip()
    host = (
        request.headers.get("x-forwarded-host")
        or request.headers.get("host")
        or request.url.netloc
    )
    host = (host or "").split(",")[0].strip()
    return f"{proto}://{host}" if host else ""


def build_verify_url(base: str, user_code: str) -> str:
    return f"{base}{VERIFY_PATH}?code={user_code}" if base else f"{VERIFY_PATH}?code={user_code}"


# ---------- 端点实现 ----------

async def start(request: Request) -> dict:
    """TV 端申请设备码。顺带清一次过期码（廉价，避免表里堆垃圾）。"""
    try:
        db.cleanup_device_codes()
    except Exception as exc:  # noqa: BLE001 清理失败不影响主流程
        logger.warning("清理过期设备码失败: %s", exc)

    info = db.create_device_code(DEVICE_CODE_TTL_SECONDS)
    base = _verify_base(request)
    return {
        "device_code": info["device_code"],
        "user_code": info["user_code"],
        "verify_url": build_verify_url(base, info["user_code"]),
        "expires_in": DEVICE_CODE_TTL_SECONDS,
        "interval": DEVICE_POLL_INTERVAL_SECONDS,
    }


async def poll(device_code: str | None) -> dict:
    """TV 轮询。统一返回 200 + status 字段，让 TV 端用一条分支处理所有情况。

    status: pending / confirmed / consumed / denied / expired
    """
    if not isinstance(device_code, str) or not device_code.strip():
        raise HTTPException(400, "缺少 device_code")
    code = device_code.strip()

    row = db.get_device_code(code)
    now = int(time.time())
    if row is None:
        return {"status": "expired"}
    if now > row["expires_at"]:
        return {"status": "expired"}

    status = row["status"]

    if status == "pending":
        return {
            "status": "pending",
            "interval": DEVICE_POLL_INTERVAL_SECONDS,
            "expires_in": max(0, row["expires_at"] - now),
        }

    if status == "confirmed":
        # 原子领取：并发/重复 poll 时只有一个能拿到行，其余落回 consumed
        consumed = db.consume_device_code(code)
        if consumed is None:
            return {"status": "consumed"}
        user = db.get_user(consumed["user_id"]) if consumed.get("user_id") is not None else None
        if user is None:
            # 用户已被删除等异常：不给 token，让 TV 重新出码
            logger.warning("设备码 %s 绑定的用户不存在", code[:8])
            return {"status": "expired"}
        token = db.create_token(user["id"], TOKEN_TTL_SECONDS)
        logger.info("扫码登录成功: user=%s", user["username"])
        return {
            "status": "confirmed",
            "token": token,
            "expires_in": TOKEN_TTL_SECONDS,
            "user_id": user["id"],
            "username": user["username"],
        }

    if status == "denied":
        return {"status": "denied"}

    # consumed：本次会话已领取过 token
    return {"status": "consumed"}


async def confirm(user_code: str | None, request: Request, user_id: int) -> dict:
    """手机端确认授权（需已登录）。"""
    if not _rate_limit_ok(_client_ip(request)):
        raise HTTPException(429, "尝试过于频繁，请稍后再试")

    if not isinstance(user_code, str) or not user_code.strip():
        raise HTTPException(400, "缺少 user_code")
    # 人读码展示为大写、无连字符；用户手输可能有空格，做一次归一
    code = user_code.strip().upper().replace(" ", "").replace("-", "")

    result, _ = db.confirm_device_code(code, user_id)
    if result == "ok":
        return {"ok": True, "status": "confirmed"}
    if result == "not_found":
        raise HTTPException(404, "确认码无效，请核对电视上显示的数字")
    if result == "expired":
        raise HTTPException(410, "确认码已过期，请在电视上重新获取")
    raise HTTPException(409, "该确认码已被使用")


async def deny(user_code: str | None, request: Request, user_id: int) -> dict:
    """手机端拒绝授权。"""
    if not _rate_limit_ok(_client_ip(request)):
        raise HTTPException(429, "尝试过于频繁，请稍后再试")
    if not isinstance(user_code, str) or not user_code.strip():
        raise HTTPException(400, "缺少 user_code")
    code = user_code.strip().upper().replace(" ", "").replace("-", "")
    ok = db.deny_device_code(code)
    if not ok:
        raise HTTPException(409, "该确认码不存在或已被处理")
    return {"ok": True, "status": "denied"}
