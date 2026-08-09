package com.tudouni.tv.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.tudouni.tv.data.SourceSwitcher
import com.tudouni.tv.data.TvRepository
import com.tudouni.tv.data.VideoItem
import com.tudouni.tv.player.PlayerController
import com.tudouni.tv.ui.components.EpisodeGrid
import com.tudouni.tv.ui.components.TvButton
import com.tudouni.tv.ui.components.TvButtonStyle
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放页（对应设计方案 §6.5）：
 * - 左 65%：Media3 播放器（16:9，PlayerView 自带控制条）
 * - 右 35%：片名 + 选集网格（当前集 accent 高亮，0-9 跳集，焦点移动即切集）
 * - 进度记忆（跨设备断点续播）：
 *   ① 恢复：进入时 seekTo(resumePositionMs)（详情页已判定是否值得恢复）
 *   ② 上报：播放中每 10s + 退出页面时一次 → PUT /api/history（服务端按 user_id 隔离，天然跨设备）
 *
 * H2 修复：监听播放器错误/缓冲状态 → 错误浮层（重试/换源/返回）+ 缓冲中加载指示
 * M1 修复：换集立即上报（force=true，即使时长未知也更新集数）
 * L5 修复：播放页保持屏幕常亮
 */
@Composable
fun PlayerScreen(
    item: VideoItem,
    url: String,
    episodes: List<String>,
    episodeIndex: Int,
    resumePositionMs: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val controller = remember { PlayerController(context) }
    val scope = rememberCoroutineScope()

    // 换源后 item / 选集变化，用 state 承接
    var currentItem by remember { mutableStateOf(item) }
    var epsState by remember { mutableStateOf(episodes) }

    var currentIndex by remember {
        mutableIntStateOf(episodeIndex.coerceIn(0, (epsState.size - 1).coerceAtLeast(0)))
    }
    // 展示用：当前是否已恢复进度（提示条）
    var showResumeTip by remember { mutableStateOf(resumePositionMs > 0) }

    // 换源状态
    var switchingSource by remember { mutableStateOf(false) }
    var switchError by remember { mutableStateOf<String?>(null) }

    // H2：播放错误 / 缓冲状态
    val playerError by controller.error.collectAsState()
    val buffering by controller.isBuffering.collectAsState()

    // 初始播放（带恢复位置）
    LaunchedEffect(Unit) {
        controller.play(url, resumePositionMs)
    }

    // H2：播放错误出现时把焦点移到浮层「重试」按钮（避免焦点仍停留在选集网格）
    val errorRetryFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(playerError) {
        if (playerError != null) {
            errorRetryFocus.requestFocus()
        }
    }

    // 播放中每 10s 上报进度（rememberUpdatedState 保证读到最新集数；fire-and-forget，失败静默）
    val latestIndex by rememberUpdatedState(currentIndex)
    LaunchedEffect(controller) {
        while (true) {
            delay(TvRepository.PROGRESS_REPORT_INTERVAL_MS)
            val pos = controller.currentPositionMs()
            val dur = controller.durationMs()
            TvRepository.reportProgress(
                item = currentItem,
                episodes = epsState,
                episodeIndex = latestIndex,
                positionMs = pos,
                durationMs = dur,
            )
        }
    }

    // 退出页面：立即上报一次 + 释放播放器 + 恢复屏幕常亮 + 解绑 PlayerView
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    DisposableEffect(controller) {
        onDispose {
            val pos = controller.currentPositionMs()
            val dur = controller.durationMs()
            if (pos > 0 && dur > 0) {
                CoroutineScope(Dispatchers.IO).launch {
                    TvRepository.reportProgress(currentItem, epsState, currentIndex, pos, dur)
                }
            }
            playerViewRef?.player = null
            controller.release()
        }
    }

    // L5：播放页保持屏幕常亮（TV 长时间观看不触发屏保/休眠）
    val activity = remember { context.findActivity() }
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BackHandler(onBack = onBack)

    fun switchEpisode(index: Int) {
        if (index !in epsState.indices) return
        currentIndex = index
        showResumeTip = false
        controller.playEpisode(epsState[index])
        // M1：立即上报换集标记（force=true 允许 duration=0），供下次恢复定位集数
        scope.launch {
            TvRepository.reportProgress(currentItem, epsState, index, 0L, 0L, force = true)
        }
    }

    // 换源：遍历内置源找同片名 → 拉替代源详情 → 播当前集
    fun switchSource() {
        if (switchingSource) return
        scope.launch {
            switchingSource = true
            switchError = null
            try {
                val alts = SourceSwitcher.findAlternatives(currentItem.vodName, currentItem.sourceCode)
                val alt = alts.firstOrNull()
                if (alt == null) {
                    switchError = "没有其他可用来源"
                    return@launch
                }
                val detail = SourceSwitcher.loadDetail(alt)
                val newEps = detail?.episodes ?: emptyList()
                val newIndex = currentIndex.coerceIn(0, (newEps.size - 1).coerceAtLeast(0))
                val newUrl = newEps.getOrNull(newIndex)
                if (newUrl == null) {
                    switchError = "替代源（${alt.sourceName}）暂无可用地址"
                    return@launch
                }
                // 切换到替代源：更新 item/选集，从当前集 0 位置播放
                currentItem = alt
                epsState = newEps
                currentIndex = newIndex
                showResumeTip = false
                controller.playEpisode(newUrl)
                TvRepository.reportProgress(currentItem, epsState, newIndex, 0L, 0L, force = true)
            } catch (e: Exception) {
                switchError = "换源失败：${e.message}"
            } finally {
                switchingSource = false
            }
        }
    }

    Row(Modifier.fillMaxSize().background(TvColors.BgBase)) {
        // 左：播放器
        Column(
            modifier = Modifier
                .weight(0.65f)
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = controller.player
                            useController = true
                        }
                    },
                    update = { view -> playerViewRef = view },
                    modifier = Modifier.fillMaxSize(),
                )
                // U6：缓冲中加载指示（无错误时显示）
                if (buffering && playerError == null && !switchingSource) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = TvColors.Accent, modifier = Modifier.width(48.dp).height(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("缓冲中…", style = TvType.BodyMedium, color = TvColors.TextSecondary)
                    }
                }
                // H2：播放错误浮层（重试 / 换源 / 返回）
                playerError?.let { err ->
                    val switchErrMsg = switchError
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(TvColors.Scrim),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = err.message,
                                style = TvType.RowTitle,
                                color = TvColors.Danger,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 40.dp),
                            )
                            if (!switchErrMsg.isNullOrBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(switchErrMsg, style = TvType.BodyMedium, color = TvColors.TextTertiary)
                            }
                            Spacer(Modifier.height(28.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                TvButton(
                                    text = if (switchingSource) "换源中…" else "换源",
                                    style = TvButtonStyle.Secondary,
                                    enabled = !switchingSource,
                                    onClick = { switchSource() },
                                )
                                TvButton(
                                    text = "重试",
                                    style = TvButtonStyle.Secondary,
                                    onClick = { controller.play(epsState[currentIndex], 0L) },
                                    modifier = Modifier.focusRequester(errorRetryFocus),
                                )
                                TvButton(
                                    text = "返回",
                                    onClick = onBack,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = currentItem.vodName ?: "",
                style = TvType.RowTitle.copy(fontSize = 22.sp),
                color = TvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (currentItem.sourceName != null && currentItem.sourceName != item.sourceName) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "来源：${currentItem.sourceName}",
                    style = TvType.Caption.copy(fontSize = 16.sp),
                    color = TvColors.Accent,
                )
            }
            if (showResumeTip) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "已从上次进度继续播放（第 ${currentIndex + 1} 集）",
                    style = TvType.Caption,
                    color = TvColors.Success,
                )
            }
        }

        // 右：选集
        Column(
            modifier = Modifier
                .weight(0.35f)
                .fillMaxSize()
                .background(TvColors.BgSurface)
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Text(
                text = "选集（${epsState.size}）",
                style = TvType.RowTitle.copy(fontSize = 24.sp),
                color = TvColors.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "方向键选择 · 数字键跳集",
                style = TvType.Caption,
                color = TvColors.TextTertiary,
            )
            Spacer(Modifier.height(20.dp))
            EpisodeGrid(
                count = epsState.size,
                currentIndex = currentIndex,
                onSelect = { index -> switchEpisode(index) },
                initialFocusIndex = currentIndex,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 从 Compose Context 找宿主 Activity（用于 FLAG_KEEP_SCREEN_ON）。 */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
