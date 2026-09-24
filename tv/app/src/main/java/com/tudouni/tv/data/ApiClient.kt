package com.tudouni.tv.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit 单例。后端地址固定（线上部署 https://tv.lanxi.me），token 运行时可变，
 * 通过 OkHttp 拦截器把占位 baseUrl 重写为目标服务器，并附加 Bearer token。
 */
object ApiClient {

    /** 线上后端（固定，无需用户配置）。 */
    const val DEFAULT_SERVER = "https://tv.lanxi.me"

    @Volatile
    private var api: TudouniApi? = null

    @Volatile
    var serverAddr: String = DEFAULT_SERVER
        private set

    @Volatile
    var token: String? = null

    /**
     * 登录态失效广播（HTTP 401）：token 过期或被服务端吊销。
     *
     * 为什么放在拦截器而不是各页面：401 可能来自任意接口（首页 / 分类 / 搜索 / 历史 /
     * 详情 / 换源 / 进度上报），逐页判断既遗漏又重复。这里统一识别一次，
     * 由 App 顶层收集后清除本机凭证并跳登录页（各页面的「重试」按钮对失效 token 无效）。
     *
     * 用 SharedFlow(replay=0) 而不是 StateFlow：只广播「失效」这一个事件，
     * 登录页停留期间不会有历史值被重放导致误跳；重复提示由 App 顶层的
     * authExpiredPending / authExpiredConsumed 两个标记兜底（每次登录只提示一次）。
     */
    private val _authExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authExpired: SharedFlow<Unit> = _authExpired.asSharedFlow()

    /** 本机是否持有凭证（无凭证时 401 属于「未登录」，不该弹「登录已过期」）。 */
    private fun hasToken(): Boolean = !token.isNullOrEmpty()

    /** 收到 401 时广播登录态失效（fire-and-forget，非阻塞、不抛异常）。 */
    private fun notifyAuthExpired() {
        _authExpired.tryEmit(Unit)
    }

    /**
     * 容错 Gson（2026-08-25 加固）：观看历史等共享表数据可能含旧版本/Web 端写入的
     * 脏类型，HistoryItemDeserializer 宽松解析，防止单条脏数据导致整个列表反序列化失败。
     * 其余模型保持 Gson 默认严格行为。
     */
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(HistoryItem::class.java, HistoryItemDeserializer())
        .create()

    /** 更新 token 并使缓存的 Retrofit 实例失效（登录前传 null 清空，成功后传新 token）。 */
    fun configure(token: String?) {
        this.token = token
        api = null
    }

    fun get(): TudouniApi {
        api?.let { return it }
        synchronized(this) {
            api?.let { return it }
            val rewrite = Interceptor { chain ->
                val req = chain.request()
                val base = serverAddr.toHttpUrlOrNull()
                val response = if (base != null) {
                    val newUrl = req.url.newBuilder()
                        .scheme(base.scheme)
                        .host(base.host)
                        .port(base.port)
                        .build()
                    val builder = req.newBuilder().url(newUrl)
                    token?.let { builder.header("Authorization", "Bearer $it") }
                    chain.proceed(builder.build())
                } else {
                    chain.proceed(req)
                }
                // 统一识别登录态失效（401）：带 token 时说明是过期/被吊销；未带 token
                // （如 /api/auth/login 密码错误返 401）不广播，否则登录页会跳回自身。
                if (response.code == 401 && hasToken()) notifyAuthExpired()
                response
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(rewrite)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl("http://placeholder/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
            return retrofit.create(TudouniApi::class.java).also { api = it }
        }
    }
}
