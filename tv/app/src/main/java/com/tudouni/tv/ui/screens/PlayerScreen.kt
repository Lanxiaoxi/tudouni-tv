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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.tudouni.tv.data.SettingsPreference
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
 * - 左 70%：Media3 播放器（16:9，PlayerView 自带控制条）
 * - 右 30%：片名 + 选集网格（当前集 accent 高亮，0-9 跳集，焦点移动即切集）
 * - 进度记忆（跨设备断点续播）：
 *   ① 恢复：进入时 seekTo(resumePositionMs)（详情页已判定是否值得恢复）
 *   ② 上报：播放中每 10s + 退出页面时一次 → PUT /api/history（服务端按 user_id 隔离，天然跨设备）
 *
 * H2 修复：监听播放器错误/缓冲状态 → 错误浮层（重试/换源/返回）+ 缓冲中加载指示
 * M1 修复：换集立即上报（force=true，即使时长未知也更新集数）
 * L5 修复：播放页保持屏幕常亮
 * 2026-08-13：全屏时返回/全屏按钮自动隐藏（3s 无操作），方向键/OK 唤醒
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

    // 自动连播设置
    val settingsPreference = remember { SettingsPreference(context) }
    var autoplayEnabled by remember {
        mutableStateOf(settingsPreference.isAutoplayEnabled())
    }

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

    // 切换剧集：更新当前集 + 立即播放 + 上报换集标记（M1：force=true 允许 duration=0）
    // 局部函数需在 LaunchedEffect 使用前声明（Kotlin 局部函数必须先声明后使用）
    fun switchEpisode(index: Int) {
        if (index !in epsState.indices) return
        currentIndex = index
        showResumeTip = false
        controller.playEpisode(epsState[index])
        scope.launch {
            TvRepository.reportProgress(currentItem, epsState, index, 0L, 0L, force = true)
        }
    }

    // 监听播放结束并自动连播
    val playbackEnded by controller.playbackEnded.collectAsState()
    LaunchedEffect(playbackEnded) {
        if (playbackEnded) {
            if (autoplayEnabled && currentIndex < epsState.size - 1) {
                // 自动连播开启 && 有下一集
                delay(1000)  // 1 秒延迟，给用户反应时间
                switchEpisode(currentIndex + 1)
            }
            // 重置标记，否则播放下一集后还会触发
            controller.resetPlaybackEnded()
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

    // 全屏：隐藏右侧选集栏 + 底部标题区，播放器占满整个页面
    var isFullscreen by remember { mutableStateOf(false) }
    // 全屏时顶部控制条（返回/全屏按钮）可见性：初始显示，3s 无操作自动隐藏，方向键/OK 唤醒
    var controlsVisible by remember { mutableStateOf(true) }
    // 唤醒/交互计数：每次按键 +1，触发计时器重启（重新计算 3s）
    var controlTick by remember { mutableIntStateOf(0) }
    val fullscreenButtonFocus = remember { FocusRequester() }

    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            controlsVisible = true // 进入全屏先显示控制条（退出后再进入时重置）
            if (playerError == null) {
                delay(100) // 等待按钮组合完成
                runCatching { fullscreenButtonFocus.requestFocus() }
            }
        }
    }

    // 全屏控制条自动隐藏计时器：显示后 3s 无操作隐藏；controlTick 变化即重启
    LaunchedEffect(isFullscreen, controlsVisible, controlTick) {
        if (isFullscreen && controlsVisible) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // 返回键：全屏中先退出全屏，非全屏才真正返回上一页
    BackHandler(onBack = {
        if (isFullscreen) isFullscreen = false else onBack()
    })

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

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.BgBase)
            // 全屏控制条交互：方向键/OK 键唤醒隐藏的控制条（隐藏时消费按键并重新聚焦）；
            // 控制条可见时放行按键（按钮正常接收焦点导航），仅重启自动隐藏计时
            .onPreviewKeyEvent { event ->
                if (isFullscreen && event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                        Key.Enter, Key.DirectionCenter -> {
                            val wasHidden = !controlsVisible
                            controlTick++ // 任何交互都重启自动隐藏计时
                            if (wasHidden) {
                                controlsVisible = true
                                if (playerError == null) {
                                    scope.launch {
                                        delay(100) // 等按钮重新组合后再聚焦
                                        runCatching { fullscreenButtonFocus.requestFocus() }
                                    }
                                }
                                true // 唤醒：消费本次按键，避免落到隐藏的按钮/播放器上
                            } else {
                                false // 可见：放行，按钮正常接收焦点导航
                            }
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
    ) {
        // 左：播放器（全屏时占满整个页面，隐藏右侧选集栏）
        Column(
            modifier = Modifier
                .weight(if (isFullscreen) 1f else 0.70f)
                .fillMaxSize()
                .padding(if (isFullscreen) 0.dp else 24.dp),
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
                // 全屏时控制条自动隐藏：按钮透明但保留组合（避免焦点丢失后按键无法唤醒）
                val controlsShown = !isFullscreen || controlsVisible
                // 返回按钮：左上角常驻（行为与遥控器返回键一致：全屏先退全屏）
                if (playerError == null) {
                    TvButton(
                        text = "← 返回",
                        style = TvButtonStyle.Secondary,
                        fontSize = 18.sp,
                        compact = true,
                        onClick = { if (isFullscreen) isFullscreen = false else onBack() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                            .alpha(if (controlsShown) 1f else 0f),
                    )
                }
                // 全屏切换按钮：右上角，常驻显示，遥控器可直接聚焦（compact 小尺寸）
                if (playerError == null) {
                    TvButton(
                        text = if (isFullscreen) "退出全屏" else "全屏",
                        style = TvButtonStyle.Secondary,
                        fontSize = 18.sp,
                        compact = true,
                        onClick = { isFullscreen = !isFullscreen },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .focusRequester(fullscreenButtonFocus)
                            .alpha(if (controlsShown) 1f else 0f),
                    )
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
            if (!isFullscreen) {
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
        }

        // 右：选集（全屏时隐藏）
        if (!isFullscreen) {
            Column(
                modifier = Modifier
                    .weight(0.30f)
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
                    // 70:30 布局下选集列变窄，集数框缩小 30%（96×64 → 67×45，26sp → 18sp）
                    cellWidth = 67.dp,
                    cellHeight = 45.dp,
                    fontSize = 18.sp,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 全屏时顶部控制条无操作自动隐藏的延迟时长。 */
private const val CONTROLS_AUTO_HIDE_MS = 3_000L

/** 从 Compose Context 找宿主 Activity（用于 FLAG_KEEP_SCREEN_ON）。 */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
