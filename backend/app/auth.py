"""Token 登录态鉴权（多用户版）。

- POST /api/auth/register  注册新用户（用户名 + 密码，密码 pbkdf2 哈希+盐入库）
- POST /api/auth/login     登录，签发 32 字节随机 token（绑定 user_id，存 SQLite）
- POST /api/auth/logout    注销当前 token
- 后续请求带 Authorization: Bearer <token> 由 require_token 校验并注入 user_id

PASSWORD 环境变量已退役：不再有全局单密码，改为每用户独立账号。
"""

import re

from fastapi import Header, HTTPException

from . import db
from .config import TOKEN_TTL_SECONDS

_USERNAME_RE = re.compile(r"^[\w\u4e00-\u9fa5]{2,32}$")  # 2-32 位：字母数字下划线中文
_MIN_PASSWORD_LEN = 6


def _validate_username(username: str) -> None:
    if not isinstance(username, str) or not _USERNAME_RE.fullmatch(username):
        raise HTTPException(400, "用户名需为 2-32 位字母/数字/下划线/中文")


def _validate_password(password: str) -> None:
    if not isinstance(password, str) or len(password) < _MIN_PASSWORD_LEN:
        raise HTTPException(400, f"密码长度至少 {_MIN_PASSWORD_LEN} 位")


async def register(username: str, password: str) -> dict:
    _validate_username(username)
    _validate_password(password)
    try:
        user_id = db.create_user(username, password)
    except Exception as exc:  # sqlite3.IntegrityError: 用户名重复
        raise HTTPException(409, "用户名已被注册") from exc

    token = db.create_token(user_id, TOKEN_TTL_SECONDS)
    return {"token": token, "expires_in": TOKEN_TTL_SECONDS, "user_id": user_id, "username": username}


async def login(username: str, password: str) -> dict:
    if not isinstance(username, str) or not isinstance(password, str):
        raise HTTPException(400, "缺少用户名或密码")
    user = db.get_user_by_name(username)
    if user is None or not db.verify_password(password, user["password_hash"], user["salt"]):
        raise HTTPException(401, "用户名或密码错误")

    token = db.create_token(user["id"], TOKEN_TTL_SECONDS)
    return {
        "token": token,
        "expires_in": TOKEN_TTL_SECONDS,
        "user_id": user["id"],
        "username": user["username"],
    }


async def logout(token: str) -> None:
    db.revoke_token(token)


def _extract_token(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "未登录或凭证缺失")
    token = authorization[7:].strip()
    if not token:
        raise HTTPException(401, "未登录或凭证缺失")
    return token


async def require_token(authorization: str | None = Header(default=None)) -> int:
    """校验 Bearer token，返回 user_id（供依赖注入使用）。"""
    token = _extract_token(authorization)
    user_id = db.resolve_token(token)
    if user_id is None:
        raise HTTPException(401, "凭证无效或已过期")
    return user_id


async def require_token_optional(authorization: str | None = Header(default=None)) -> int | None:
    """可选鉴权：带有效 token 返回 user_id，否则返回 None（不报错）。"""
    if not authorization or not authorization.startswith("Bearer "):
        return None
    return db.resolve_token(authorization[7:].strip())
