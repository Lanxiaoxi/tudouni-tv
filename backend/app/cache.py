"""进程内 TTL 缓存（内存 dict，服务搜索/详情等实时端点）。

设计：
- 存的是**处理后、准备返回给前端的最终 data**（命中直接 return，跳过整个下游）
- TTL 惰性失效：请求到来时检查 expires_at，过期视为未命中，重新拉取覆盖
- LRU 条数上限：超过 MAX_ITEMS 淘汰最久未使用的 key，防止内存无限增长
- 写入时顺带清理过期条目（不依赖额外定时任务）

只缓存只读聚合数据（无用户维度），用户数据端点（history 等）绝不使用本缓存。
"""

import threading
import time
from collections import OrderedDict

from .config import TTL_CACHE_MAX_ITEMS


class TTLCache:
    def __init__(self, max_items: int = TTL_CACHE_MAX_ITEMS):
        self._max_items = max_items
        self._data: OrderedDict[str, tuple[float, object]] = OrderedDict()
        self._lock = threading.Lock()

    def get(self, key: str):
        """命中且未过期返回 value；过期/不存在返回 None（并移除过期项）。"""
        with self._lock:
            entry = self._data.get(key)
            if entry is None:
                return None
            expires_at, value = entry
            if time.time() > expires_at:
                del self._data[key]  # 惰性失效
                return None
            self._data.move_to_end(key)  # 更新 LRU
            return value

    def set(self, key: str, value, ttl: int | None = None) -> None:
        """写入缓存。ttl 为空使用默认（CACHE_TTL_SECONDS 语义由调用方传入）。"""
        if ttl is None:
            ttl = 300  # 兜底默认 5 分钟
        with self._lock:
            self._data[key] = (time.time() + ttl, value)
            self._data.move_to_end(key)
            self._trim()

    def _trim(self) -> None:
        # 顺带清掉已过期条目，再按 LRU 上限淘汰
        now = time.time()
        expired = [k for k, (exp, _) in self._data.items() if now > exp]
        for k in expired:
            del self._data[k]
        while len(self._data) > self._max_items:
            self._data.popitem(last=False)  # 淘汰最久未用的

    def clear(self) -> None:
        with self._lock:
            self._data.clear()

    def __len__(self) -> int:
        with self._lock:
            return len(self._data)


# 全局单例
_cache = TTLCache()


def cache_get(key: str):
    return _cache.get(key)


def cache_set(key: str, value, ttl: int | None = None) -> None:
    _cache.set(key, value, ttl)
