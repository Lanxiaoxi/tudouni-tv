package com.tudouni.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.tudouni.tv.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 软件更新（2026-08-22 新增，配合后端 /api/app/version + /api/app/download）：
 * - [check]：拉版本信息（无需登录），失败返回 null 由 UI 提示
 * - [download]：OkHttp 流式下载 APK 到 cacheDir/update/，带进度回调
 * - [install]：FileProvider 共享 + 系统安装器；未授权"安装未知应用"返回 false 由 UI 引导
 *
 * 版本对比用 versionCode（Int），不用版本字符串（避免 "0.10.0" < "0.9.0" 之类问题）。
 */
object AppUpdater {

    private const val TAG = "AppUpdater"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // APK 一般 10-30MB，读超时放宽
        .build()

    /** 是否需要更新：服务端 latest_code > 当前 versionCode。 */
    fun isUpdateAvailable(latestCode: Int): Boolean = latestCode > BuildConfig.VERSION_CODE

    /** 把后端相对下载路径拼成完整 URL（绝对 URL 原样返回）。 */
    fun resolveDownloadUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http://") || path.startsWith("https://")) path
        else ApiClient.serverAddr.trimEnd('/') + "/" + path.trimStart('/')
    }

    /** 下载的 APK 目标文件（cacheDir/update/tudounitv.apk，与 res/xml/file_paths.xml 对应）。 */
    private fun apkFile(context: Context): File {
        val dir = File(context.cacheDir, "update")
        dir.mkdirs()
        return File(dir, "tudounitv.apk")
    }

    /** 拉取版本信息；请求/解析失败返回 null。 */
    suspend fun check(): AppVersionData? = withContext(Dispatchers.IO) {
        try {
            val resp = ApiClient.get().appVersion()
            if (!resp.isSuccessful || resp.body()?.code != 0) return@withContext null
            resp.body()?.data
        } catch (e: Exception) {
            Log.w(TAG, "检查更新失败", e)
            null
        }
    }

    /**
     * 流式下载 APK，返回文件；失败返回 null 并清理残留文件。
     * @param onProgress 进度回调 (downloadedBytes, totalBytes)；total 未知时为 0。
     */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Long, Long) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val target = apkFile(context)
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                val source = body.source()
                val buffer = okio.Buffer()
                target.outputStream().buffered().use { out ->
                    var downloaded = 0L
                    while (true) {
                        val read = source.read(buffer, 8192)
                        if (read == -1L) break
                        out.write(buffer.readByteArray())
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                    out.flush()
                }
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "下载失败", e)
            target.delete()
            null
        }
    }

    /** 是否已授权"安装未知应用"（Android 8+；minSdk 26，无需版本判断）。 */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 调起系统安装器；返回 false 表示失败（未授权/无安装器），由 UI 引导。 */
    fun install(context: Context, apk: File): Boolean {
        if (!canInstall(context)) return false
        return try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "启动安装器失败", e)
            false
        }
    }

    /** 引导用户去系统"允许安装未知应用"设置页（返回后需重新点「检查更新」重试）。 */
    fun openInstallPermissionSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "打开安装来源设置失败", e)
        }
    }
}
