package com.tudouni.tv.data

/**
 * 首页首批数据预拉缓存：App 开屏（Screen.Loading）期间后台拉取 /api/items 首批 500 条，
 * 进入 HomeScreen 时直接消费 → 开屏图结束即见首页内容，中间不再出现加载画面。
 * 单次有效：consume() 取走即清空（避免切页/重进用旧数据）。预拉失败保持空，HomeScreen 自行加载兜底。
 */
object HomePrefetch {
    var items: List<VideoItem> = emptyList()
        private set
    var total: Int = 0
        private set

    /** 开屏期间调用（suspend，等数据到位才返回）：拉首批 500 条并应用分级过滤。 */
    suspend fun load(filterEnabled: Boolean) {
        try {
            val resp = ApiClient.get().items(offset = 0, limit = 500)
            val body = resp.body()
            val data = body?.data
            if (resp.isSuccessful && body != null && body.code == 0 && data != null) {
                items = if (filterEnabled) ContentFilter.filterItems(data.items) else data.items
                total = data.total
            }
        } catch (_: Exception) {
            // 预拉失败不阻塞启动：HomeScreen 会自行加载
        }
    }

    /** HomeScreen 组合时消费：取走即清空。 */
    fun consume(): Pair<List<VideoItem>, Int> {
        val result = items to total
        items = emptyList()
        total = 0
        return result
    }
}
