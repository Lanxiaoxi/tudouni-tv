package com.tudouni.tv.data

/**
 * 分类页内存缓存（方案二）：按 cat 缓存列表数据，TTL 100 分钟。
 * 进程级单例——分类页互切 / 进详情页返回都能命中，避免重复请求 /api/vodlist。
 * key = "$cat|$filterEnabled"：内容分级过滤状态变化时缓存自然失效。
 */
object CategoryCache {
    /** 缓存有效期：100 分钟。 */
    const val TTL_MS = 100 * 60 * 1000L

    private data class Entry(
        val items: List<VideoItem>,
        val total: Int,
        val page: Int,
        val timestamp: Long,
    )

    private val map = mutableMapOf<String, Entry>()

    /** 命中且未过期 → 返回 (items, total, page)；过期条目惰性移除并返回 null。 */
    fun get(cat: String, filterEnabled: Boolean): Triple<List<VideoItem>, Int, Int>? {
        val key = keyOf(cat, filterEnabled)
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > TTL_MS) {
            map.remove(key)
            return null
        }
        return Triple(entry.items, entry.total, entry.page)
    }

    /** 写缓存（load 成功后调用，存当前 items 全量/总数/页码）。 */
    fun put(cat: String, filterEnabled: Boolean, items: List<VideoItem>, total: Int, page: Int) {
        map[keyOf(cat, filterEnabled)] = Entry(items, total, page, System.currentTimeMillis())
    }

    private fun keyOf(cat: String, filterEnabled: Boolean) = "$cat|$filterEnabled"
}
