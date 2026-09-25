package com.tudouni.tv.data

import kotlinx.coroutines.CancellationException

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
        for ((key, _) in KNOWN_SOURCES) {
            if (key == excludeSource) continue
            try {
                // 必须带 source：不带的话 10 次循环发的是同一个全源聚合请求，
                // 返回条目的 vod_id 属于"某个源"，拿去请求 /api/detail?source=<别的源> 必然查不到。
                val resp = ApiClient.get().search(wd = title, page = 1, source = key)
                if (!resp.isSuccessful) continue
                val data = resp.body()?.data ?: continue
                // 信任后端按源过滤后打的 source_code / vod_id 组合，不再手工改标签。
                val hit = data.items.firstOrNull {
                    it.vodName == title && it.sourceCode == key && !it.vodId.isNullOrBlank()
                }
                if (hit != null) result.add(hit)
            } catch (e: CancellationException) {
                // 超时/离开页面导致的取消必须继续上抛，否则 withTimeoutOrNull 之类的
                // 限时会因为这里吞掉 CancellationException 而失效
                throw e
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
