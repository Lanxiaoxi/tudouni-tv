package com.tudouni.tv.data

import com.google.gson.annotations.SerializedName
import org.json.JSONObject
import retrofit2.Response

/**
 * 后端可能返回相对路径（封面本地化 /covers/xxx.jpg、上游 m3u8 一般是绝对 URL）。
 * 客户端需把封面/视频相对路径拼接 baseUrl 才是可加载的完整 URL。
 * 与 ApiClient 拦截器同源（读 serverAddr），避免未来支持自定义服务器时双源分叉。
 */
fun resolveMediaUrl(path: String?): String? {
    if (path.isNullOrBlank()) return null
    return if (path.startsWith("http://") || path.startsWith("https://")) path
    else ApiClient.serverAddr.trimEnd('/') + "/" + path.trimStart('/')
}

/**
 * 后端用 HTTP 状态码表达业务错误（如 401/400/409），body 为 {code, message}。
 * Retrofit 默认对非 2xx 抛 HttpException，这里解析 errorBody 的 message 展示给用户。
 */
fun <T> Response<T>.errorMessage(): String {
    val body = errorBody()?.string()
    if (body.isNullOrBlank()) return "请求失败（HTTP ${code()}）"
    return try {
        val msg = JSONObject(body).optString("message")
        msg.ifEmpty { "请求失败（HTTP ${code()}）" }
    } catch (e: Exception) {
        "请求失败（HTTP ${code()}）"
    }
}

/**
 * 后端统一响应包装（/api/items、/api/auth 成功 code=0；
 * 注意 /api/detail 成功 code=200，单独定义 DetailResponse 处理）。
 */
data class ApiResponse<T>(
    val code: Int,
    val data: T?,
    val message: String?
)

/** POST /api/auth/login | register → data */
data class LoginResult(
    val token: String,
    @SerializedName("expires_in") val expiresIn: Long,
    @SerializedName("user_id") val userId: Long,
    val username: String
)

/** GET /api/items?offset=&limit= → data（分批加载） */
data class ItemsData(
    val items: List<VideoItem>,
    val total: Int,
    val offset: Int,
    @SerializedName("has_more") val hasMore: Boolean
)

/** /api/items 列表项（字段对齐上游 vod_* / 镜像表 _video_row） */
data class VideoItem(
    @SerializedName("source_name") val sourceName: String?,
    @SerializedName("source_code") val sourceCode: String?,
    @SerializedName("vod_id") val vodId: String?,
    @SerializedName("vod_name") val vodName: String?,
    @SerializedName("type_name") val typeName: String?,
    @SerializedName("vod_pic") val pic: String?,
    @SerializedName("vod_remarks") val remarks: String?,
    @SerializedName("vod_area") val area: String?,
    @SerializedName("vod_year") val year: String?,
    @SerializedName("vod_play_url") val playUrl: String?
)

/** GET /api/detail?id=&source= → 顶层结构（code=200 为成功） */
data class DetailResponse(
    val code: Int,
    val episodes: List<String>?,
    val detailUrl: String?,
    val videoInfo: VideoInfo?
)

/** /api/detail → videoInfo */
data class VideoInfo(
    val title: String?,
    val cover: String?,
    val desc: String?,
    val type: String?,
    val year: String?,
    val area: String?,
    val director: String?,
    val actor: String?,
    val remarks: String?,
    @SerializedName("source_name") val sourceName: String?,
    @SerializedName("source_code") val sourceCode: String?
)

// ---------- 搜索 / 分类列表（/api/search、/api/vodlist 同构） ----------

/** /api/search?wd= 或 /api/vodlist?cat= 的 data（items 字段与 /api/items 一致，都是 vod_* 兼容结构） */
data class VodListData(
    val total: Int,
    val items: List<VideoItem>,
    val page: Int
)

// ---------- 观看历史（/api/history，字段对齐后端 viewing_history 行） ----------

data class HistoryData(
    val items: List<HistoryItem>,
    val total: Int
)

/** viewing_history 行。episodes 为后端存回的 JSON 数组（m3u8 地址列表）。 */
data class HistoryItem(
    val id: Long?,
    @SerializedName("vod_id") val vodId: String?,
    val source: String?,
    val title: String?,
    val pic: String?,
    val episodes: List<String>?,
    @SerializedName("episode_index") val episodeIndex: Int?,
    val position: Double?,
    val duration: Double?,
    val timestamp: Long?
) {
    /** 转成可打开详情/续播的 VideoItem。 */
    fun toVideoItem(): VideoItem = VideoItem(
        sourceName = source,
        sourceCode = source,
        vodId = vodId,
        vodName = title,
        typeName = null,
        pic = pic,
        remarks = null,
        area = null,
        year = null,
        playUrl = null,
    )
}

// ---------- 搜索历史（/api/search-history） ----------

data class SearchHistoryData(
    val items: List<SearchHistoryItem>,
    val total: Int
)

data class SearchHistoryItem(
    val id: Long?,
    val keyword: String?,
    val timestamp: Long?
)

// ---------- 账号（/api/me） ----------

data class MeData(
    val id: Long?,
    val username: String?,
    val role: String?,
    val settings: Map<String, Any>? = null
)
