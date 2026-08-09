package com.tudouni.tv.data

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
                if (base != null) {
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
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(rewrite)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl("http://placeholder/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            return retrofit.create(TudouniApi::class.java).also { api = it }
        }
    }
}
