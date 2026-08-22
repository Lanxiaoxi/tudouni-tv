package com.tudouni.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tudouni.tv.BuildConfig
import com.tudouni.tv.data.AppUpdater
import com.tudouni.tv.data.AppVersionData
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.launch

/** 软件更新流程状态机（2026-08-22 新增）。 */
private sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    data class Prompt(val info: AppVersionData) : UpdateState
    object UpToDate : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Error(val message: String) : UpdateState
    /** 未授权"安装未知应用"，需引导去系统设置。 */
    object NeedInstallPermission : UpdateState
}

/**
 * 软件更新弹窗流程（放在 App 主框架层，覆盖所有页面）：
 * - [autoCheck]：进入主界面时自动检查一次（调用方用一次性标记控制，返回主界面不重复弹）
 * - [checkTrigger]：设置页「检查更新」手动触发（计数递增）
 * 流程：检查中 → 发现新版（弹窗：立即更新/稍后）→ 下载（进度）→ 系统安装器；
 * 已最新 / 失败 / 缺安装权限 均有对应弹窗。
 */
@Composable
fun UpdateFlow(
    autoCheck: Boolean,
    checkTrigger: Int,
    onAutoCheckConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    // 自动检查：静默模式——无更新/失败都不弹窗（避免每次启动打扰），仅发现新版才提示；
    // 手动检查（设置页）：完整反馈（检查中遮罩 / 已最新 / 失败都展示）
    suspend fun runCheck(silentOnNoUpdate: Boolean) {
        if (!silentOnNoUpdate) state = UpdateState.Checking
        val info = AppUpdater.check()
        if (info == null) {
            state = if (silentOnNoUpdate) UpdateState.Idle
            else UpdateState.Error("检查更新失败，请检查网络后重试")
        } else if (!AppUpdater.isUpdateAvailable(info.latestCode)) {
            state = if (silentOnNoUpdate) UpdateState.Idle else UpdateState.UpToDate
        } else {
            state = UpdateState.Prompt(info)
        }
    }

    // 启动自动检查（仅当 autoCheck 为 true 的首次组合；调用方消费后置 false）
    LaunchedEffect(Unit) {
        if (autoCheck) {
            onAutoCheckConsumed()
            runCheck(silentOnNoUpdate = true)
        }
    }

    // 设置页「检查更新」手动触发
    LaunchedEffect(checkTrigger) {
        if (checkTrigger > 0) runCheck(silentOnNoUpdate = false)
    }

    when (val s = state) {
        UpdateState.Idle -> Unit

        UpdateState.Checking -> UpdateProgressOverlay(text = "正在检查更新…", progress = null)

        is UpdateState.Prompt -> TvDialog(
            title = "发现新版本 v${s.info.latestVersion}",
            message = buildString {
                if (!s.info.notes.isNullOrBlank()) {
                    append(s.info.notes)
                    append("\n\n")
                }
                append("当前版本 v${BuildConfig.VERSION_NAME} → 新版本 v${s.info.latestVersion}")
            },
            confirmText = "立即更新",
            cancelText = "稍后",
            onConfirm = {
                // 先确认安装权限，避免下载完才发现装不了
                if (!AppUpdater.canInstall(context)) {
                    state = UpdateState.NeedInstallPermission
                } else {
                    state = UpdateState.Downloading(0f)
                    scope.launch {
                        val url = AppUpdater.resolveDownloadUrl(s.info.downloadUrl)
                        if (url == null) {
                            state = UpdateState.Error("下载地址无效，请稍后重试")
                            return@launch
                        }
                        val apk = AppUpdater.download(context, url) { downloaded, total ->
                            state = UpdateState.Downloading(
                                if (total > 0) downloaded.toFloat() / total else 0f
                            )
                        }
                        if (apk == null) {
                            state = UpdateState.Error("下载失败，请重试")
                        } else if (!AppUpdater.install(context, apk)) {
                            state = UpdateState.NeedInstallPermission
                        } else {
                            state = UpdateState.Idle
                        }
                    }
                }
            },
            onDismiss = { state = UpdateState.Idle },
        )

        UpdateState.UpToDate -> TvDialog(
            title = "已是最新版本",
            message = "当前版本 v${BuildConfig.VERSION_NAME} 已是最新",
            confirmText = "好的",
            cancelText = "取消",
            onConfirm = { state = UpdateState.Idle },
            onDismiss = { state = UpdateState.Idle },
        )

        is UpdateState.Downloading -> UpdateProgressOverlay(
            text = "正在下载更新…",
            progress = s.progress,
        )

        is UpdateState.Error -> TvDialog(
            title = "更新失败",
            message = s.message,
            confirmText = "重试",
            cancelText = "取消",
            onConfirm = {
                state = UpdateState.Idle
                scope.launch { runCheck(silentOnNoUpdate = false) }
            },
            onDismiss = { state = UpdateState.Idle },
        )

        UpdateState.NeedInstallPermission -> TvDialog(
            title = "需要允许安装应用",
            message = "首次更新需要开启「安装未知应用」权限。请前往系统设置开启后返回，再点击「检查更新」重试。",
            confirmText = "去设置",
            cancelText = "取消",
            onConfirm = {
                state = UpdateState.Idle
                AppUpdater.openInstallPermissionSettings(context)
            },
            onDismiss = { state = UpdateState.Idle },
        )
    }
}

/** 检查中/下载中的全屏遮罩（带进度百分比；progress 为 null 时显示不定进度圈）。 */
@Composable
private fun UpdateProgressOverlay(text: String, progress: Float?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (progress == null) {
                CircularProgressIndicator(
                    color = TvColors.Accent,
                    modifier = Modifier.size(64.dp),
                )
            } else {
                CircularProgressIndicator(
                    progress = { progress },
                    color = TvColors.Accent,
                    modifier = Modifier.size(64.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(text = text, style = TvType.BodyMedium, color = TvColors.TextPrimary)
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = TvType.Caption,
                    color = TvColors.TextSecondary,
                )
            }
        }
    }
}
