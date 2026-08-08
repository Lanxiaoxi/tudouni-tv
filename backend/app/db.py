"""SQLite 数据库层（多用户改造）。

使用 Python 内置 sqlite3（零依赖），数据文件 backend/data.db。
表：
- users            用户（username 唯一，密码 pbkdf2 哈希+盐）
- tokens           登录 token（绑定 user_id，带过期时间）
- viewing_history  观看历史（进度并入：position/duration；剧集快照存 JSON）
- search_history   搜索历史

所有读写函数均为同步 sqlite3 调用；FastAPI 端点在 async 函数里直接调用
（SQLite 操作极快，单机规模无性能问题）。每个请求用独立连接，避免跨线程共享。
"""

import hashlib
import json
import os
import secrets
import sqlite3
import time
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent
DB_PATH = Path(os.getenv("LIBRETV_DB", str(BACKEND_DIR / "data.db")))

_ISOLATION_LEVEL = None  # 自动提交模式，配合显式 BEGIN/COMMIT 使用


def get_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(str(DB_PATH), timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def init_db() -> None:
    """建表（幂等）。启动时调用一次。"""
    conn = get_conn()
    try:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                username      TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                salt          TEXT NOT NULL,
                role          TEXT NOT NULL DEFAULT 'user',
                settings      TEXT NOT NULL DEFAULT '{}',
                created_at    INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS tokens (
                token      TEXT PRIMARY KEY,
                user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                expires_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_tokens_user ON tokens(user_id);

            CREATE TABLE IF NOT EXISTS viewing_history (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                vod_id        TEXT,
                source        TEXT,
                title         TEXT NOT NULL,
                pic           TEXT,
                episodes      TEXT,
                episode_index INTEGER DEFAULT 0,
                position      REAL DEFAULT 0,
                duration      REAL DEFAULT 0,
                timestamp     INTEGER NOT NULL,
                UNIQUE(user_id, vod_id, source)
            );
            CREATE INDEX IF NOT EXISTS idx_history_user ON viewing_history(user_id, timestamp);

            CREATE TABLE IF NOT EXISTS search_history (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                keyword   TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_search_user ON search_history(user_id, timestamp);
            """
        )
        conn.commit()
        # 存量库迁移：早期 users 表没有 role 列，补上（幂等）
        cols = [r["name"] for r in conn.execute("PRAGMA table_info(users)").fetchall()]
        if "role" not in cols:
            conn.execute("ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'user'")
            conn.commit()
    finally:
        conn.close()


# ---------- 密码哈希 ----------

def hash_password(password: str, salt: str | None = None) -> tuple[str, str]:
    """pbkdf2 哈希 + 随机盐。返回 (password_hash, salt)。"""
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt.encode("utf-8"), 100_000
    )
    return digest.hex(), salt


def verify_password(password: str, password_hash: str, salt: str) -> bool:
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt.encode("utf-8"), 100_000
    )
    return secrets.compare_digest(digest.hex(), password_hash)


# ---------- 用户 ----------

def create_user(username: str, password: str) -> int:
    """创建用户，用户名重复抛 sqlite3.IntegrityError。返回 user_id。"""
    password_hash, salt = hash_password(password)
    conn = get_conn()
    try:
        cur = conn.execute(
            "INSERT INTO users (username, password_hash, salt, created_at) VALUES (?,?,?,?)",
            (username, password_hash, salt, int(time.time())),
        )
        conn.commit()
        return cur.lastrowid
    finally:
        conn.close()


def get_user_by_name(username: str) -> sqlite3.Row | None:
    conn = get_conn()
    try:
        return conn.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
    finally:
        conn.close()


def get_user(user_id: int) -> sqlite3.Row | None:
    conn = get_conn()
    try:
        return conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    finally:
        conn.close()


# ---------- Token ----------

def create_token(user_id: int, ttl_seconds: int) -> str:
    token = secrets.token_hex(32)
    conn = get_conn()
    try:
        conn.execute(
            "INSERT INTO tokens (token, user_id, expires_at) VALUES (?,?,?)",
            (token, user_id, int(time.time()) + ttl_seconds),
        )
        conn.commit()
    finally:
        conn.close()
    return token


def resolve_token(token: str) -> int | None:
    """校验 token，返回 user_id；无效/过期返回 None（并顺带删除过期行）。"""
    conn = get_conn()
    try:
        row = conn.execute("SELECT user_id, expires_at FROM tokens WHERE token = ?", (token,)).fetchone()
        if row is None:
            return None
        if time.time() > row["expires_at"]:
            conn.execute("DELETE FROM tokens WHERE token = ?", (token,))
            conn.commit()
            return None
        return row["user_id"]
    finally:
        conn.close()


def revoke_token(token: str) -> None:
    conn = get_conn()
    try:
        conn.execute("DELETE FROM tokens WHERE token = ?", (token,))
        conn.commit()
    finally:
        conn.close()


# ---------- 观看历史 ----------

def upsert_history(user_id: int, item: dict) -> None:
    """按 (user_id, vod_id, source) upsert。vod_id/source 为空时退化为插入。"""
    conn = get_conn()
    try:
        if item.get("vod_id") and item.get("source"):
            conn.execute(
                """
                INSERT INTO viewing_history
                    (user_id, vod_id, source, title, pic, episodes,
                     episode_index, position, duration, timestamp)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(user_id, vod_id, source) DO UPDATE SET
                    title=excluded.title, pic=excluded.pic, episodes=excluded.episodes,
                    episode_index=excluded.episode_index, position=excluded.position,
                    duration=excluded.duration, timestamp=excluded.timestamp
                """,
                (
                    user_id,
                    item.get("vod_id"),
                    item.get("source"),
                    item.get("title", ""),
                    item.get("pic"),
                    json.dumps(item.get("episodes") or [], ensure_ascii=False),
                    item.get("episode_index", 0),
                    item.get("position", 0),
                    item.get("duration", 0),
                    int(item.get("timestamp") or time.time()),
                ),
            )
        else:
            conn.execute(
                """
                INSERT INTO viewing_history
                    (user_id, title, pic, episodes, episode_index, position, duration, timestamp)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                (
                    user_id,
                    item.get("title", ""),
                    item.get("pic"),
                    json.dumps(item.get("episodes") or [], ensure_ascii=False),
                    item.get("episode_index", 0),
                    item.get("position", 0),
                    item.get("duration", 0),
                    int(item.get("timestamp") or time.time()),
                ),
            )
        conn.commit()
    finally:
        conn.close()


def get_history(user_id: int, limit: int = 50) -> list[dict]:
    conn = get_conn()
    try:
        rows = conn.execute(
            "SELECT * FROM viewing_history WHERE user_id = ? ORDER BY timestamp DESC LIMIT ?",
            (user_id, limit),
        ).fetchall()
        return [_history_row(r) for r in rows]
    finally:
        conn.close()


def clear_history(user_id: int) -> None:
    conn = get_conn()
    try:
        conn.execute("DELETE FROM viewing_history WHERE user_id = ?", (user_id,))
        conn.commit()
    finally:
        conn.close()


def delete_history_item(user_id: int, vod_id: str | None = None, source: str | None = None, title: str | None = None) -> bool:
    """删除单条历史。优先按 (vod_id, source)，否则按 title 兜底。返回是否删除。"""
    conn = get_conn()
    try:
        cur = None
        if vod_id and source:
            cur = conn.execute(
                "DELETE FROM viewing_history WHERE user_id = ? AND vod_id = ? AND source = ?",
                (user_id, vod_id, source),
            )
        elif vod_id:
            cur = conn.execute(
                "DELETE FROM viewing_history WHERE user_id = ? AND vod_id = ?",
                (user_id, vod_id),
            )
        elif title:
            cur = conn.execute(
                "DELETE FROM viewing_history WHERE user_id = ? AND title = ?",
                (user_id, title),
            )
        conn.commit()
        return bool(cur and cur.rowcount > 0)
    finally:
        conn.close()


def _history_row(r: sqlite3.Row) -> dict:
    d = dict(r)
    try:
        d["episodes"] = json.loads(d.get("episodes") or "[]")
    except (ValueError, TypeError):
        d["episodes"] = []
    return d


# ---------- 搜索历史 ----------

def get_search_history(user_id: int, limit: int = 50) -> list[dict]:
    conn = get_conn()
    try:
        rows = conn.execute(
            "SELECT * FROM search_history WHERE user_id = ? ORDER BY timestamp DESC LIMIT ?",
            (user_id, limit),
        ).fetchall()
        return [dict(r) for r in rows]
    finally:
        conn.close()


def add_search_history(user_id: int, keyword: str) -> None:
    conn = get_conn()
    try:
        conn.execute(
            "INSERT INTO search_history (user_id, keyword, timestamp) VALUES (?,?,?)",
            (user_id, keyword, int(time.time())),
        )
        conn.commit()
    finally:
        conn.close()


def delete_search_history_item(user_id: int, keyword: str) -> bool:
    conn = get_conn()
    try:
        cur = conn.execute(
            "DELETE FROM search_history WHERE user_id = ? AND keyword = ?",
            (user_id, keyword),
        )
        conn.commit()
        return bool(cur.rowcount > 0)
    finally:
        conn.close()


def clear_search_history(user_id: int) -> None:
    conn = get_conn()
    try:
        conn.execute("DELETE FROM search_history WHERE user_id = ?", (user_id,))
        conn.commit()
    finally:
        conn.close()
