package com.tudouni.tv.data

/**
 * 首页数据缓存：App 开屏（Screen.Loading）期间预拉 /api/items 首批 500 条，
 * 或 HomeScreen 加载成功后写入；进入/切回首页时读取——TTL 100 分钟内秒开，
 * 避免每次切回主页都重新请求首批数据。进程级单例，惰性过期。
 * 缓存绑定内容分级过滤状态：filterEnabled 变化时缓存作废（避免用错过滤规则的数据）。
 */
object HomePrefetch {
    /** 缓存有效期：100 分钟（与 CategoryCache 一致）。 */
    const val TTL_MS = 100 * 60 * 1000L

    private data class Entry(
        val items: List<VideoItem>,
        val total: Int,
        val filterEnabled: Boolean,
        val timestamp: Long,
    )

    private var cache: Entry? = null

    /** 开屏期间调用（suspend，等数据到位才返回）：拉首批 500 条并应用分级过滤后写缓存。 */
    suspend fun load(filterEnabled: Boolean) {
        try {
            val resp = ApiClient.get().items(offset = 0, limit = 500)
            val body = resp.body()
            val data = body?.data
            if (resp.isSuccessful && body != null && body.code == 0 && data != null) {
                val items = if (filterEnabled) ContentFilter.filterItems(data.items) else data.items
                put(filterEnabled, items, data.total)
            }
        } catch (_: Exception) {
            // 预拉失败不阻塞启动：HomeScreen 会自行加载
        }
    }

    /** 读取缓存：命中且未过期且过滤状态一致才返回 (items, total)，否则 null（惰性清空）。 */
    fun get(filterEnabled: Boolean): Pair<List<VideoItem>, Int>? {
        val entry = cache ?: return null
        if (entry.filterEnabled != filterEnabled) {
            cache = null
            return null
        }
        if (System.currentTimeMillis() - entry.timestamp > TTL_MS) {
            cache = null
            return null
        }
        return entry.items to entry.total
    }

    /** 写缓存（HomeScreen 首屏加载成功后调用；覆盖旧缓存并刷新时间戳）。 */
    fun put(filterEnabled: Boolean, items: List<VideoItem>, total: Int) {
        cache = Entry(items, total, filterEnabled, System.currentTimeMillis())
    }
}
