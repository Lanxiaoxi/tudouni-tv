#!/usr/bin/env python3
"""下载 videos 表全部封面到 covers/，文件名=URL 哈希，幂等可断点续传。

用法: python3 tools/download_covers.py [并发数]
默认 8 并发。已存在的文件自动跳过，失败会打印并继续。
"""
import hashlib
import os
import sqlite3
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB = os.path.join(BASE, "backend", "data.db")
COVERS = os.path.join(BASE, "covers")
EXTS = (".jpg", ".png", ".webp")

os.makedirs(COVERS, exist_ok=True)


def _existing_path(name: str) -> str | None:
    for ext in EXTS:
        p = os.path.join(COVERS, name + ext)
        if os.path.exists(p) and os.path.getsize(p) > 0:
            return p
    return None


def fetch(url: str):
    name = hashlib.sha256(url.encode()).hexdigest()[:16]
    exist = _existing_path(name)
    if exist:
        return url, exist, "skip"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=15) as r:
            data = r.read()
        ctype = (r.headers.get("Content-Type") or "").lower()
        ext = ".png" if "png" in ctype else (".webp" if "webp" in ctype else ".jpg")
        path = os.path.join(COVERS, name + ext)
        with open(path, "wb") as f:
            f.write(data)
        return url, path, "ok"
    except Exception as e:  # noqa: BLE001
        return url, None, f"fail:{e}"


def main():
    workers = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    db = sqlite3.connect(DB)
    urls = [r[0] for r in db.execute('select pic from videos where pic like "http%"')]
    print(f"待处理: {len(urls)} 张, 并发 {workers}", flush=True)
    ok = skip = fail = 0
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=workers) as ex:
        for url, path, status in ex.map(fetch, urls):
            if status == "ok":
                ok += 1
            elif status == "skip":
                skip += 1
            else:
                fail += 1
                print("失败:", status, url[:90], flush=True)
    print(f"完成: 新下载 {ok}, 跳过 {skip}, 失败 {fail}, 耗时 {time.time()-t0:.0f}s", flush=True)
    print(f"封面目录: {COVERS}")


if __name__ == "__main__":
    main()
