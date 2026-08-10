package com.tudouni.tv.data

/**
 * 换源工具（M5/H2 修复）：资源站单源失效时，用同一片名在其他源中找替代。
 *
 * 原理：后端 /api/search 支持 `source` 参数（指定单个源聚合），
 * 遍历内置源列表，用当前片名精确搜索，命中同名即得到该源的 VideoItem（含该源 vod_id），
 * 再调 /api/detail 拉取该源选集。
 */
object SourceSwitcher {

    /** 内置源列表（与后端 backend/app/sites.py SITES 对齐；新源需同步）。 */
    val KNOWN_SOURCES: List<Pair<String, String>> = listOf(
        "jinying" to "金鹰资源",
        "guangsu" to "光速资源",
        "uku" to "U酷资源",
        "baidu" to "百度资源",
        "wujin" to "无尽资源",
        "subo" to "速博资源",
        "modu" to "魔都资源",
        "zuidazy" to "最大资源",
        "huohu" to "火狐资源",
        "dadi" to "大地资源",
    )

    /**
     * 找同片名的其他来源（排除 [excludeSource]）。
     * 逐源调 /api/search?wd=title&source=key，取第一个 vod_name 精确同名的条目。
     * @return 命中的其他源 VideoItem 列表（含该源 vod_id/sourceCode）
     */
    suspend fun findAlternatives(title: String?, excludeSource: String?): List<VideoItem> {
        if (title.isNullOrBlank()) return emptyList()
        val result = mutableListOf<VideoItem>()
        for ((key, name) in KNOWN_SOURCES) {
            if (key == excludeSource) continue
            try {
                val resp = ApiClient.get().search(wd = title, page = 1)
                if (!resp.isSuccessful) continue
                val body = resp.body() ?: continue
                val data = body.data ?: continue
                val hit = data.items.firstOrNull { it.vodName == title && it.sourceCode != excludeSource }
                if (hit != null) {
                    result.add(
                        hit.copy(
                            sourceCode = key,
                            sourceName = name,
                        )
                    )
                }
            } catch (_: Exception) {
                // 单源探测失败跳过
            }
        }
        return result
    }

    /** 拉取指定源条目的详情（选集成片）。 */
    suspend fun loadDetail(item: VideoItem): DetailResponse? {
        return try {
            val resp = ApiClient.get().detail(id = item.vodId ?: "", source = item.sourceCode)
            if (resp.isSuccessful) resp.body() else null
        } catch (_: Exception) {
            null
        }
    }
}
