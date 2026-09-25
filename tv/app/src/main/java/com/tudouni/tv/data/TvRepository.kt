package com.tudouni.tv.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 业务仓库：进度记忆（跨设备断点续播）的读写封装。
 *
 * 数据存服务端（/api/history 按 user_id 隔离）→ 天然跨设备：
 * 任意设备登录同一账号，进入详情/播放页即可恢复上次进度与集数。
 */
object TvRepository {

    private const val TAG = "TvRepository"

    /** 进度上报节流间隔（播放中每 10s 一次 + 暂停/退出时兜底一次）。 */
    const val PROGRESS_REPORT_INTERVAL_MS = 10_000L

    /** 小于该进度视为「从头开始」，不恢复。 */
    const val RESUME_MIN_MS = 30_000L

    /** 距片尾小于该值视为「已看完」，从头开始。 */
    const val RESUME_TAIL_MARGIN_MS = 30_000L

    /** 串行化进度上报：避免 10s 轮询与手动上报并发导致乱序（旧进度覆盖新进度）。 */
    private val reportMutex = Mutex()

    /**
     * 播放进度上报（PUT /api/history）。
     * @param episodes 当前剧集的 m3u8 地址列表（存回服务端供跨设备续播用）
     * @param positionMs 当前播放位置毫秒
     * @param durationMs 总时长毫秒
     * @param force 为 true 时即使 duration<=0 也上报（换集标记场景：仅更新集数，进度归零）
     * 正常播放进度在拿不到时长（duration<=0）时不上报，避免把未知时长写成 0。
     *
     * 注意：本方法不抛异常（网络错误返回 false）。Retrofit 的 suspend 方法在 DNS/超时/连接重置时
     * 会抛 IOException，而调用点（播放页 10s 轮询、退出兜底）都在协程里且没有 try/catch，
     * 异常会沿协程上抛到 UncaughtExceptionHandler 直接把 App 打崩。
     */
    suspend fun reportProgress(
        item: VideoItem,
        episodes: List<String>,
        episodeIndex: Int,
        positionMs: Long,
        durationMs: Long,
        timestamp: Long = System.currentTimeMillis() / 1000,
        force: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (durationMs <= 0 && !force) return@withContext false
        if (item.vodName.isNullOrBlank()) return@withContext false
        try {
            reportMutex.withLock {
                val resp = ApiClient.get().putHistory(
                    HistoryBody(
                        title = item.vodName ?: "",
                        vodId = item.vodId ?: "",
                        source = item.sourceCode ?: "",
                        pic = item.pic ?: "",
                        episodes = episodes,
                        episodeIndex = episodeIndex,
                        position = positionMs / 1000.0,
                        duration = durationMs / 1000.0,
                        timestamp = timestamp,
                    )
                )
                resp.isSuccessful
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "进度上报失败（${item.vodName} @ ${positionMs}ms）", e)
            false
        }
    }

    /**
     * 拉取观看历史（最新在前）。
     * @throws IOException 网络/服务端错误时抛出（由页面区分「空历史」与「加载失败」）；
     *   401 抛 [AUTH_EXPIRED_MESSAGE]，页面据此不再引导「重试」
     */
    suspend fun fetchHistory(limit: Int = 100): List<HistoryItem> = withContext(Dispatchers.IO) {
        val resp = ApiClient.get().history(limit)
        if (resp.isSuccessful) {
            val body = resp.body()
            if (body != null && body.code == 0 && body.data != null) body.data.items ?: emptyList()
            else throw IOException(body?.message ?: "获取历史失败（HTTP ${resp.code()}）")
        } else {
            throw IOException(resp.pageErrorMessage())
        }
    }

    /** 按 (vod_id, source) 在历史中找记录（用于进入详情/播放页时恢复进度）。 */
    suspend fun findHistory(vodId: String?, source: String?): HistoryItem? {
        if (vodId.isNullOrBlank()) return null
        return fetchHistory(200).firstOrNull {
            it.vodId == vodId && (source.isNullOrBlank() || it.source == source)
        }
    }

    /**
     * 是否值得恢复进度：位置在 [RESUME_MIN_MS, duration - RESUME_TAIL_MARGIN_MS] 之间。
     * 刚看几秒 / 已看到片尾 → 从头播。
     */
    fun shouldResume(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs <= 0 || durationMs <= 0) return false
        return positionMs >= RESUME_MIN_MS && positionMs <= durationMs - RESUME_TAIL_MARGIN_MS
    }

    /** 删除单条观看历史（网络失败返回 false，不抛异常）。 */
    suspend fun deleteHistoryItem(item: HistoryItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val resp = ApiClient.get().deleteHistoryItem(
                vodId = item.vodId,
                source = item.source,
                title = item.title,
            )
            resp.isSuccessful
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "删除历史失败", e)
            false
        }
    }

    /** 清空全部观看历史（网络失败返回 false，不抛异常）。 */
    suspend fun clearHistory(): Boolean = withContext(Dispatchers.IO) {
        try {
            ApiClient.get().clearHistory().isSuccessful
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "清空历史失败", e)
            false
        }
    }

    // ---------- 搜索历史 ----------

    /**
     * 拉取搜索历史。
     * @throws IOException 网络/服务端错误时抛出（由页面区分「空」与「失败」）
     */
    suspend fun fetchSearchHistory(limit: Int = 30): List<SearchHistoryItem> = withContext(Dispatchers.IO) {
        val resp = ApiClient.get().searchHistory(limit)
        if (resp.isSuccessful) {
            val body = resp.body()
            if (body != null && body.code == 0 && body.data != null) body.data.items
            else throw IOException(body?.message ?: "获取搜索历史失败（HTTP ${resp.code()}）")
        } else {
            throw IOException(resp.pageErrorMessage())
        }
    }

    suspend fun addSearchHistory(keyword: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val resp = ApiClient.get().postSearchHistory(mapOf("keyword" to keyword))
            resp.isSuccessful
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "写入搜索历史失败", e)
            false
        }
    }
}
