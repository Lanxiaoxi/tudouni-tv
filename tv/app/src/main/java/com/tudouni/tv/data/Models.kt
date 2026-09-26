package com.tudouni.tv.data

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import org.json.JSONObject
import retrofit2.Response
import java.lang.reflect.Type

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

// ---------- 登录态失效（401）识别 ----------

/**
 * 该响应是否表示登录态失效（token 过期 / 被吊销 / 本机未携带）。
 *
 * 用途：页面拿到 401 时不再展示「重试」——重试用的还是同一个失效 token，必然再失败。
 * 提示改为引导重新登录；真正清凭证 + 跳登录页由 ApiClient.authExpired → App 顶层弹窗完成。
 */
fun <T> Response<T>.isAuthExpired(): Boolean = code() == 401

/** 登录态失效时给用户的统一文案（与 App 顶层「登录已过期」弹窗口径一致）。 */
const val AUTH_EXPIRED_MESSAGE = "登录已过期，请重新登录"

/**
 * 后端用 HTTP 状态码表达业务错误（如 401/400/409），body 为 {code, message}。
 * Retrofit 默认对非 2xx 抛 HttpException，这里解析 errorBody 的 message 展示给用户。
 *
 * 注意保留后端原文：登录接口的 401 是「用户名或密码错误」，不能归一化成「登录已过期」
 * （需要归一化的页面错误请用 [pageErrorMessage]）。
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
 * 页面级错误文案：401 归一化为 [AUTH_EXPIRED_MESSAGE]，其余沿用后端原文。
 *
 * 业务接口的 401 只可能来自 token 失效（后端 require_token 的两种情况），此时后端原文
 * 「凭证无效或已过期」配上「重试」按钮会被理解成临时故障，实际重试必然再失败。
 * 登录接口不要用这个（那里的 401 是密码错误，见 [errorMessage]）。
 */
fun <T> Response<T>.pageErrorMessage(): String =
    if (isAuthExpired()) AUTH_EXPIRED_MESSAGE else errorMessage()

/**
 * 异常 → 页面展示文案。
 * 登录态失效（[TvRepository] 抛出的 IOException）不再冠以「网络错误」——
 * 它和网络无关，误导用户去检查网络。
 */
fun Throwable.userFacingError(): String =
    if (message == AUTH_EXPIRED_MESSAGE) AUTH_EXPIRED_MESSAGE else "网络错误: $message"

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

// ---------- 扫码登录 TV 端（设备码授权，见 docs/tv-qr-login-design.md） ----------

/** POST /api/auth/device/start → data */
data class DeviceStartData(
    /** 本机轮询凭据，不上屏、不进二维码。 */
    @SerializedName("device_code") val deviceCode: String,
    /** 人读确认码：显示在电视上，供用户与手机核对。 */
    @SerializedName("user_code") val userCode: String,
    /** 二维码内容（后端按部署域名拼好的确认页绝对地址）。 */
    @SerializedName("verify_url") val verifyUrl: String,
    @SerializedName("expires_in") val expiresIn: Long,
    /** 后端建议的轮询间隔（秒）。 */
    val interval: Long
)

/**
 * POST /api/auth/device/poll → data。
 *
 * 接口**统一返回 HTTP 200**，成功/等待/失败都靠 [status] 区分，故其他字段全部可空：
 * - `pending`：还在等手机确认，可能带 interval / expires_in
 * - `confirmed`：带 token / username / user_id / expires_in（本次会话已一次性领走）
 * - `consumed`：已被领走过（重复轮询）
 * - `denied`：用户在手机上拒绝了
 * - `expired`：设备码不存在或已过期
 */
data class DevicePollData(
    // 声明为可空：Gson 绕过构造器默认值，字段缺失时会写入 null；
    // 若声明为非空 String，null 会在 when 比对处留下隐患（后端新增字段/异常响应时）
    val status: String? = null,
    val token: String? = null,
    @SerializedName("expires_in") val expiresIn: Long? = null,
    @SerializedName("user_id") val userId: Long? = null,
    val username: String? = null,
    val interval: Long? = null
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_CONSUMED = "consumed"
        const val STATUS_DENIED = "denied"
        const val STATUS_EXPIRED = "expired"
    }
}

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

/**
 * PUT /api/history 的请求体。
 *
 * 必须用具体类型而不能用 Map<String, Any>：Kotlin 的 Map<V> 中 V 是 out（协变）位置，
 * Map<String, Any> 编译成 Java 后会被投影为 Map<String, ? extends Object>（含通配符），
 * Retrofit 校验 @Body 参数时直接抛 "Parameter type must not include a type variable or
 * wildcard"。data class 字段类型明确，Gson 序列化字段名走 @SerializedName。
 */
data class HistoryBody(
    val title: String,
    @SerializedName("vod_id") val vodId: String,
    val source: String,
    val pic: String,
    val episodes: List<String>,
    @SerializedName("episode_index") val episodeIndex: Int,
    val position: Double,
    val duration: Double,
    val timestamp: Long,
)

data class HistoryData(
    val items: List<HistoryItem>?,
    val total: Int
)

/**
 * 观看历史行反序列化容错（2026-08-25 加固）。
 *
 * viewing_history 表被 Web 端与 TV 端多版本共同写入，线上存在脏数据类型：
 * position/duration/episode_index/timestamp 可能是字符串、episodes 可能是字符串或对象、
 * id 可能是字符串等。Gson 默认严格类型解析遇到不匹配会抛 JsonSyntaxException，
 * 导致整个历史列表反序列化失败（历史页打不开/报网络错误）。本适配器全部宽松解析：
 * - 数值字段：接受 数字 / 数字字符串 / null，解析失败按缺省；
 * - episodes：只取 JSON 数组里的字符串（数字元素转字符串），非数组按空列表，null 保持 null；
 * - 整行都不是对象（极端脏数据）：返回空行，避免整页崩。
 */
class HistoryItemDeserializer : JsonDeserializer<HistoryItem> {

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): HistoryItem {
        if (!json.isJsonObject) {
            return HistoryItem(null, null, null, null, null, null, null, null, null, null)
        }
        val o = json.asJsonObject
        return HistoryItem(
            id = o.doubleOf("id")?.toLong(),
            vodId = o.stringOf("vod_id"),
            source = o.stringOf("source"),
            title = o.stringOf("title"),
            pic = o.stringOf("pic"),
            episodes = o.episodeListOf("episodes"),
            episodeIndex = o.doubleOf("episode_index")?.toInt(),
            position = o.doubleOf("position"),
            duration = o.doubleOf("duration"),
            timestamp = o.doubleOf("timestamp")?.toLong(),
        )
    }
}

/** 字符串字段：数字/布尔也会转成字符串（容错），null / 对象 / 数组返回 null。 */
private fun JsonObject.stringOf(name: String): String? {
    val el = get(name) ?: return null
    return if (el.isJsonPrimitive) el.asString else null
}

/** 数值字段：接受 数字 或 数字字符串，其他类型 / 解析失败返回 null。 */
private fun JsonObject.doubleOf(name: String): Double? {
    val el = get(name) ?: return null
    if (!el.isJsonPrimitive) return null
    val p = el.asJsonPrimitive
    return when {
        p.isNumber -> p.asDouble
        p.isString -> p.asString.trim().toDoubleOrNull()
        else -> null
    }
}

/** episodes 字段：只取 JSON 数组里的字符串 / 数字元素；null 返回 null，非数组（字符串/对象）按空列表。 */
private fun JsonObject.episodeListOf(name: String): List<String>? {
    val el = get(name) ?: return null
    if (el.isJsonNull) return null
    if (!el.isJsonArray) return emptyList()
    val result = mutableListOf<String>()
    for (e in el.asJsonArray) {
        if (e.isJsonPrimitive) result.add(e.asString)
    }
    return result
}

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

// ---------- 软件更新（/api/app/version，无需鉴权） ----------

/** GET /api/app/version → data（download_url 为相对路径，客户端按 serverAddr 拼接） */
data class AppVersionData(
    @SerializedName("latest_version") val latestVersion: String,
    @SerializedName("latest_code") val latestCode: Int,
    @SerializedName("download_url") val downloadUrl: String,
    val notes: String?,
    val force: Boolean
)

// ---------- 账号（/api/me） ----------

data class MeData(
    val id: Long?,
    val username: String?,
    val role: String?,
    val settings: Map<String, Any>? = null
)
