package com.tudouni.tv.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

/**
 * 现有后端 FastAPI 接口（保持后端零改动）。
 * baseUrl 为占位 host，实际地址由 ApiClient 的 OkHttp 拦截器按当前 serverAddr 重写。
 * 全部返回 Response<T>：后端用 HTTP 状态码表达业务错误（401/400/409），
 * 需手动判断 isSuccessful 并解析 errorBody 里的中文 message。
 */
interface TudouniApi {

    @POST("/api/auth/login")
    suspend fun login(@Body body: Map<String, String>): Response<ApiResponse<LoginResult>>

    @POST("/api/auth/register")
    suspend fun register(@Body body: Map<String, String>): Response<ApiResponse<LoginResult>>

    @POST("/api/auth/logout")
    suspend fun logout(): Response<ApiResponse<Map<String, Any>>>

    // ---------- 内容浏览 ----------

    @GET("/api/items")
    suspend fun items(
        @Query("offset") offset: Int,
        @Query("limit") limit: Int
    ): Response<ApiResponse<ItemsData>>

    /** 爱奇艺热播榜：后端已按榜单片名聚合资源站搜索，返回可播放条目（缓存 5 天）。 */
    @GET("/api/iqiyi/hot")
    suspend fun iqiyiHot(): Response<ApiResponse<ItemsData>>

    /** 优酷热播榜（/api/hotrank/youku，缓存 5 天）。 */
    @GET("/api/hotrank/youku")
    suspend fun youkuHot(): Response<ApiResponse<ItemsData>>

    /** 腾讯热播榜（/api/hotrank/tencent，缓存 5 天）。 */
    @GET("/api/hotrank/tencent")
    suspend fun tencentHot(): Response<ApiResponse<ItemsData>>

    @GET("/api/search")
    suspend fun search(
        @Query("wd") wd: String,
        @Query("page") page: Int = 1
    ): Response<ApiResponse<VodListData>>

    @GET("/api/vodlist")
    suspend fun vodlist(
        @Query("cat") cat: String?,
        @Query("pg") pg: Int = 1
    ): Response<ApiResponse<VodListData>>

    @GET("/api/detail")
    suspend fun detail(
        @Query("id") id: String,
        @Query("source") source: String?
    ): Response<DetailResponse>

    // ---------- 观看历史（进度记忆：上报 / 恢复 / 删除） ----------

    @GET("/api/history")
    suspend fun history(@Query("limit") limit: Int = 100): Response<ApiResponse<HistoryData>>

    @PUT("/api/history")
    suspend fun putHistory(@Body body: HistoryBody): Response<ApiResponse<Map<String, Any>>>

    @DELETE("/api/history")
    suspend fun clearHistory(): Response<ApiResponse<Map<String, Any>>>

    @DELETE("/api/history/item")
    suspend fun deleteHistoryItem(
        @Query("vod_id") vodId: String?,
        @Query("source") source: String?,
        @Query("title") title: String?
    ): Response<ApiResponse<Map<String, Any>>>

    // ---------- 搜索历史 ----------

    @GET("/api/search-history")
    suspend fun searchHistory(@Query("limit") limit: Int = 50): Response<ApiResponse<SearchHistoryData>>

    @POST("/api/search-history")
    suspend fun postSearchHistory(@Body body: Map<String, String>): Response<ApiResponse<Map<String, Any>>>

    @DELETE("/api/search-history")
    suspend fun clearSearchHistory(): Response<ApiResponse<Map<String, Any>>>

    // ---------- 软件更新（无需鉴权） ----------

    @GET("/api/app/version")
    suspend fun appVersion(): Response<ApiResponse<AppVersionData>>

    // ---------- 账号 ----------

    @GET("/api/me")
    suspend fun me(): Response<ApiResponse<MeData>>
}
