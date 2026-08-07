"""Token 登录态鉴权。

- POST /api/auth 校验密码后签发 32 字节随机 token（内存存储，TTL 可配）
- 后续请求带 Authorization: Bearer <token> 由 require_token 校验
- 密码明文只存在于服务器环境变量，不再注入任何页面源码
"""

import secrets
import time

from fastapi import Header, HTTPException

from .config import PASSWORD, TOKEN_TTL_SECONDS

_tokens: dict[str, float] = {}  # token -> 过期时间戳(epoch 秒)


async def login(password: str) -> dict:
    if not PASSWORD:
        raise HTTPException(500, "服务器未配置 PASSWORD 环境变量")
    if not secrets.compare_digest(password, PASSWORD):
        raise HTTPException(401, "密码错误")

    token = secrets.token_hex(32)
    _tokens[token] = time.time() + TOKEN_TTL_SECONDS
    return {"token": token, "expires_in": TOKEN_TTL_SECONDS}


async def require_token(authorization: str | None = Header(default=None)) -> None:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "未登录或凭证缺失")
    token = authorization[7:].strip()
    expiry = _tokens.get(token)
    if expiry is None:
        raise HTTPException(401, "凭证无效或已过期")
    if time.time() > expiry:
        _tokens.pop(token, None)
        raise HTTPException(401, "凭证已过期")
