package com.tudouni.tv.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import com.tudouni.tv.ui.components.PlayerControlsBar
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
 * - 左 70%：Media3 播放器（**原生控制条已关闭**，播放控件见下）
 * - 右 30%：片名 + 选集网格（当前集 accent 高亮，0-9 跳集，焦点移动即切集）
 * - 进度记忆（跨设备断点续播）：
 *   ① 恢复：进入时 seekTo(resumePositionMs)（详情页已判定是否值得恢复）
 *   ② 上报：播放中每 10s + 退出页面时一次 → PUT /api/history（服务端按 user_id 隔离，天然跨设备）
 *
 * H2 修复：监听播放器错误/缓冲状态 → 错误浮层（重试/换源/返回）+ 缓冲中加载指示
 * M1 修复：换集立即上报（force=true，即使时长未知也更新集数）
 * L5 修复：播放页保持屏幕常亮
 *
 * 2026-08-25 焦点架构统一（本次重构）：此前 Media3 自带控制条（原生 View 焦点系统）
 * 与本页 Compose 按钮（Compose 焦点系统）并存，跨系统转移只能按屏幕矩形猜最近邻，
 * 方向键落点不可控（焦点在「返回」按右键会跳到「退出全屏」、按下键跳到「上一集」）。
 * 现改为 `useController = false` 关闭原生控制条 + 自绘 [PlayerControlsBar]，
 * 全页只剩一套 Compose 焦点。随之删除了此前为弥合两套焦点而加的三处补丁：
 *   - OnKeyListener 视图层兜底（改为隐藏态焦点锚点，见 hiddenAnchorFocus）
 *   - 唤醒键 KeyUp 配对吞键（不再有跨系统焦点抢占，无需配对）
 *   - showController() 联动唤醒（原生控制条已不存在）
 */
// PlayerScreen 内使用 Media3 的 PlayerView（部分 API 属 @UnstableApi，需显式标注）。
// 注意：kotlin 的 @OptIn / @file:OptIn 只能过编译器，lint 的 UnsafeOptInUsageError 不认，
// 必须用 Media3 自带的 @UnstableApi 注解。
@androidx.media3.common.util.UnstableApi
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
    // 播放/暂停态（自绘控制条图标用）
    val isPlaying by controller.isPlaying.collectAsState()

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
    // 全屏控制条可见性：初始显示，3s 无操作自动隐藏，任意按键唤醒
    var controlsVisible by remember { mutableStateOf(true) }
    // 交互计数：每次按键 +1，触发计时器重启（重新计算 3s）
    var controlTick by remember { mutableIntStateOf(0) }
    // 唤醒时记住被消费的按键，成对的 KeyUp 一并吞掉。
    // 必要性：控制条隐藏时按 OK/Enter 唤醒 → 控制条组合出来且焦点落到「播放/暂停」，
    // 紧接着的 KeyUp 会被 Compose clickable 当作点击，导致刚唤醒就误触发播放/暂停。
    var wakeConsumedKey by remember { mutableStateOf<Key?>(null) }
    // 控制条按钮的焦点锚点（进入控制条 / 唤醒后落在「播放暂停」上）
    val playPauseFocus = remember { FocusRequester() }
    // 顶栏「返回」按钮的焦点锚点：控制条的「向上」路由指向它。
    // 非全屏时必须显式路由——右侧选集栏从屏幕顶部开始，按下键时几何搜索会优先选它
    // （加权距离 13×纵向²+横向²，纵向被放大 13 倍），焦点会跑到选集而非控制条。
    val topBackFocus = remember { FocusRequester() }
    // 隐藏态焦点锚点：Compose 只在焦点路径上分发按键，若整棵树都没有焦点节点，
    // 按键收不到、控制条永远唤不醒。控制条隐藏时把焦点锚定到这个占位节点上，
    // 从而不再需要 2026-08-22 那套 OnKeyListener 视图层兜底。
    val hiddenAnchorFocus = remember { FocusRequester() }

    /** 请求焦点并重试：目标节点可能尚未组合完成（慢设备上尤其明显）。 */
    suspend fun requestFocusWithRetry(fr: FocusRequester) {
        var attempts = 0
        while (attempts < FOCUS_RETRY_ATTEMPTS) {
            if (runCatching { fr.requestFocus() }.isSuccess) return
            delay(FOCUS_RETRY_MS)
            attempts++
        }
    }

    // 进入全屏：显示控制条并重启计时
    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            controlsVisible = true
            controlTick++
        }
    }

    // 全屏控制条自动隐藏：显示后 3s 无操作 → 隐藏；controlTick 变化即重启计时
    LaunchedEffect(isFullscreen, controlsVisible, controlTick) {
        if (isFullscreen && controlsVisible) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // 焦点锚定（与可见性变化绑定，避免「隐藏后锚点尚未组合」的竞态）：
    // 可见 → 焦点落在播放/暂停；隐藏 → 焦点落在占位锚点（保证按键仍能沿焦点路径到达）。
    // 出错时不动焦点：此时展示错误浮层，焦点应归浮层按钮（由 errorRetryFocus 请求），
    // 锚点若在此抢焦点会让用户无法操作浮层。
    LaunchedEffect(isFullscreen, controlsVisible, playerError) {
        if (isFullscreen && playerError == null) {
            if (controlsVisible) {
                requestFocusWithRetry(playPauseFocus)
            } else {
                requestFocusWithRetry(hiddenAnchorFocus)
            }
        }
    }

    // 自绘控制条的播放位置/时长：ExoPlayer 无「位置变化」回调（currentPosition 需轮询），
    // 这里每 500ms 采样。仅控制条可见时采样——全屏沉浸观看时不必持续刷新 UI。
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controller, controlsVisible) {
        while (true) {
            if (controlsVisible) {
                positionMs = controller.currentPositionMs()
                durationMs = controller.durationMs()
            }
            delay(CONTROLS_POSITION_POLL_MS)
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
            // 全屏按键处理：
            // - 控制条隐藏：任意方向键/OK 只唤醒并消费本次按键，不执行隐藏前的按钮
            // - 控制条可见：放行按键让按钮正常导航，仅重启自动隐藏计时
            // 关闭 Media3 原生控制条后全页只剩这一套 Compose 焦点，落点可预期。
            .onPreviewKeyEvent { event ->
                if (!isFullscreen) return@onPreviewKeyEvent false

                // 唤醒键的成对 KeyUp 必须先吞掉（放在可见性判断之前）：
                // 控制条在 KeyDown 时被唤醒、焦点随即落到「播放/暂停」，若放行 KeyUp，
                // Compose clickable 会把它当作点击（Enter/OK 的点击在 KeyUp 触发），
                // 表现为「刚唤醒就误触播放/暂停」。
                if (event.type == KeyEventType.KeyUp && event.key == wakeConsumedKey) {
                    wakeConsumedKey = null
                    return@onPreviewKeyEvent true
                }

                val isWakeKey = when (event.key) {
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                    Key.Enter, Key.DirectionCenter -> true
                    else -> false
                }
                if (!isWakeKey) return@onPreviewKeyEvent false

                if (!controlsVisible) {
                    if (event.type == KeyEventType.KeyDown) {
                        wakeConsumedKey = event.key // 记住唤醒键，其 KeyUp 一并吞掉
                        controlsVisible = true
                        controlTick++
                        true
                    } else {
                        false
                    }
                } else {
                    // 可见：任何交互都重启自动隐藏计时，按键照常放行给按钮
                    if (event.type == KeyEventType.KeyDown) controlTick++
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
                            // 2026-08-25：关闭 Media3 自带控制条，改用自绘 PlayerControlsBar。
                            // 原生控制条（播放/暂停、进度条、快进快退）位于原生 View 焦点系统，
                            // 与本页 Compose 按钮是两套焦点，跨系统转移只能按屏幕矩形猜最近邻，
                            // 方向键落点不可控（焦点在「返回」按右键会跳到「退出全屏」）。
                            useController = false
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
                // 全屏时控制条自动隐藏：不组合（而非 alpha=0）——隐藏的按钮若仍在组合中
                // 会继续参与焦点搜索，方向键可能落到看不见的按钮上。
                val controlsShown = !isFullscreen || controlsVisible
                // 隐藏态焦点锚点：1dp 不可见但可聚焦，保证隐藏时按键仍能沿焦点路径到达。
                // 出错时不放置（焦点归错误浮层按钮，锚点会抢焦点）
                if (isFullscreen && !controlsVisible && playerError == null) {
                    Box(
                        modifier = Modifier
                            .size(1.dp)
                            .align(Alignment.Center)
                            .focusRequester(hiddenAnchorFocus)
                            .focusable()
                    )
                }
                // 返回按钮：左上角（行为与遥控器返回键一致：全屏先退全屏）
                if (playerError == null && controlsShown) {
                    TvButton(
                        text = "← 返回",
                        style = TvButtonStyle.Secondary,
                        fontSize = 18.sp,
                        compact = true,
                        onClick = { if (isFullscreen) isFullscreen = false else onBack() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                            .focusRequester(topBackFocus)
                            // 「向下」显式路由到控制条「播放/暂停」：
                            // 非全屏时几何搜索会优先选右侧选集栏（它从屏幕顶部开始，
                            // 纵向距离远小于底部控制条），焦点会跑到选集去。
                            // 这样顶部↔底部导航可控，与用户预期一致。
                            .focusProperties { down = playPauseFocus },
                    )
                }
                // 全屏切换按钮：右上角
                if (playerError == null && controlsShown) {
                    TvButton(
                        text = if (isFullscreen) "退出全屏" else "全屏",
                        style = TvButtonStyle.Secondary,
                        fontSize = 18.sp,
                        compact = true,
                        onClick = { isFullscreen = !isFullscreen },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .focusProperties { down = playPauseFocus },
                    )
                }
                // 自绘播放控制条：贴底（进度条 + 播放/暂停 + 快进退 + 上下集）。
                // 关闭了 Media3 原生控制条，全页只有这一套 Compose 焦点。
                // 全屏时受自动隐藏控制；非全屏时（右侧有选集栏）常驻，便于随时操作。
                if (playerError == null && controlsShown) {
                    PlayerControlsBar(
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onTogglePlayPause = { controller.togglePlayPause() },
                        onSeekBy = { delta -> controller.seekBy(delta) },
                        onPrevEpisode = if (currentIndex > 0) {
                            { switchEpisode(currentIndex - 1) }
                        } else {
                            null
                        },
                        onNextEpisode = if (currentIndex < epsState.lastIndex) {
                            { switchEpisode(currentIndex + 1) }
                        } else {
                            null
                        },
                        // 非全屏也必须传：顶栏「向下」路由指向它，未挂载的 requester 无法作路由目标
                        playPauseFocus = playPauseFocus,
                        upFocus = topBackFocus,
                        modifier = Modifier.align(Alignment.BottomCenter),
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

/** 全屏时控制条无操作自动隐藏的延迟时长。 */
private const val CONTROLS_AUTO_HIDE_MS = 3_000L

/** 自绘控制条的位置采样间隔（ExoPlayer 无位置变化回调，只能轮询）。 */
private const val CONTROLS_POSITION_POLL_MS = 500L

/** 焦点请求重试间隔（毫秒）。 */
private const val FOCUS_RETRY_MS = 50L

/** 焦点请求重试次数上限（50ms × 20 ≈ 1s；正常情况首次即成功，仅慢设备兜底）。 */
private const val FOCUS_RETRY_ATTEMPTS = 20

/** 从 Compose Context 找宿主 Activity（用于 FLAG_KEEP_SCREEN_ON）。 */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
