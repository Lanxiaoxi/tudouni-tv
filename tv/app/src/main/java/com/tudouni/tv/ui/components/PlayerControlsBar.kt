package com.tudouni.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType

/** 快进/快退步长（毫秒）。 */
const val SEEK_STEP_MS = 15_000L

/**
 * 自绘播放控制条（替代 Media3 自带控制条）。
 *
 * ## 为什么自绘
 * Media3 的 `PlayerView` 是原生 View，它自带的控制条（播放/暂停、进度条、快进快退）
 * 位于**原生 View 焦点系统**；而播放页其他按钮位于 **Compose 焦点系统**。
 * 两个系统之间转移焦点时，Compose 只能通过 FocusFinder 按屏幕矩形几何关系猜最近邻，
 * 落点不可控（焦点在「返回」按右键会跳到几何上最近的「退出全屏」）。
 * 关闭原生控制条后全页只剩一套 Compose 焦点，方向键落点符合直觉且可预期。
 *
 * ## 布局
 * 两行：
 * ```
 * ════════════════════════════════════════════  ← 进度条（可聚焦：左右键快退/快进 15s）
 *   [⏮ 上一集?]  [⏪ 退15秒]  [▶/❚❚]  [⏩ 进15秒]  [⏭ 下一集?]
 * ```
 * 上下集并入按钮行，是为了让导航退化为一维：左右在行内移动，上下在「进度条 → 按钮行 → 顶栏」间移动。
 * 播放/暂停居中（两侧是退/进 15 秒），与常见遥控器「中间键是播放」的操作习惯一致。
 *
 * ## 焦点
 * - 进度条与按钮行都可聚焦。**进度条聚焦时左右键 = 快退/快进 10 秒**（事件被进度条
 *   消费，不会跳出；系统对按住不放的按键自动连发，长按即连续快扫）——这是 TV 播放器
 *   的标准交互（Media3 自带 TimeBar 在 TV 上同为此语义）；聚焦时进度条加粗并显示
 *   原色描边以表明当前处于「可扫动」状态
 * - 进度条「向下」固定路由到按钮行「播放/暂停」；按钮行「向上」回进度条——
 *   两侧各自的纵向落点都是显式指定的（非全屏时几何搜索会偏向屏幕顶部的选集栏，
 *   见上一版本修复说明）
 * - 左右移动在按钮行内由几何搜索处理（同一行内，结果确定）
 * - [scrubFocus] 建议传入：调用方可把外部焦点（顶栏/唤醒）直接投到进度条上
 * - 控制条需要「不抢焦点」时，调用方直接不组合它（而非传入不可聚焦标志）——
 *   不组合才能同时保证视觉隐藏与焦点不可达
 *
 * @param isPlaying 是否处于「应播放」态（决定图标与标签）。调用方应传
 *   PlayerController.playWhenReady 而不是 isPlaying：缓冲/seek 期间 isPlaying 为 false，
 *   图标会错误地显示成「▶ 播放」
 * @param positionMs 当前播放位置
 * @param durationMs 总时长（未知传 0：进度条禁用聚焦、时长显示 --:--）
 * @param onTogglePlayPause 播放/暂停
 * @param onSeekBy 相对跳转（正数快进、负数快退），单位毫秒
 * @param onPrevEpisode 上一集；null 表示当前已是第一集（按钮不出现）
 * @param onNextEpisode 下一集；null 表示当前已是最后一集（按钮不出现）
 * @param playPauseFocus 播放/暂停按钮的焦点锚点。**始终传入**（非全屏也要传）：
 *   外部路由（顶栏「向下」、进度条「向下」）指向它，未挂载的 requester 无法作为路由目标
 * @param upFocus 顶栏落点：进度条/按钮行「向上」回到顶栏（通常指向顶部「返回」按钮）
 */
@Composable
fun PlayerControlsBar(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onPrevEpisode: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    playPauseFocus: FocusRequester? = null,
    upFocus: FocusRequester? = null,
    scrubFocus: FocusRequester? = null,
) {
    val hasDuration = durationMs > 0
    val progress = if (hasDuration) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    // 进度条（扫动条）的焦点交互源：焦点态决定条的粗细与描边
    val scrubInteractionSource = remember { MutableInteractionSource() }
    val scrubFocused by scrubInteractionSource.collectIsFocusedAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, TvColors.BgBase.copy(alpha = 0.94f)),
                )
            )
            .padding(horizontal = 40.dp, vertical = 16.dp),
    ) {
        // ---- 进度条（可聚焦的 seek bar：左右键快退/快进） ----
        // 不可聚焦的纯展示条已升级为「扫动条」：聚焦时加粗 + 琥珀描边，
        // 左右键经 onKeyEvent 消费（不跳出控制条），长按靠系统连发实现连续快扫。
        // 时长未知（HLS 边下边播常见）时禁用聚焦——扫动无参照，避免死焦点。
        val seekEnabled = hasDuration
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (scrubFocused) 14.dp else 8.dp)
                .then(
                    if (scrubFocus != null) {
                        Modifier.focusRequester(scrubFocus)
                    } else {
                        Modifier
                    }
                )
                .focusable(
                    enabled = seekEnabled,
                    interactionSource = scrubInteractionSource,
                )
                // 聚焦态左右键 = 快退/快进，消费以避免焦点跳出进度条
                .onKeyEvent { event ->
                    if (!seekEnabled || event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionLeft -> {
                                onSeekBy(-SEEK_STEP_MS)
                                true
                            }

                            Key.DirectionRight -> {
                                onSeekBy(SEEK_STEP_MS)
                                true
                            }

                            else -> false
                        }
                    }
                }
                // 聚焦态琥珀描边：表明当前处于「可扫动」状态
                .then(
                    if (scrubFocused) {
                        Modifier.border(2.dp, TvColors.Accent, RoundedCornerShape(7.dp))
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(if (scrubFocused) 14.dp else 8.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(TvColors.Accent, TvColors.AccentStrong),
                            ),
                            RoundedCornerShape(if (scrubFocused) 7.dp else 4.dp),
                        ),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatDuration(positionMs),
                style = TvType.Caption.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = TvColors.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (hasDuration) formatDuration(durationMs) else "--:--",
                style = TvType.Caption.copy(fontSize = 15.sp),
                color = TvColors.TextTertiary,
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---- 按钮行 ----
        // 「向上」路由到进度条（形成 进度条 → 按钮行 → 顶栏 的三级纵向导航），
        // 显式指定而非依赖几何搜索——非全屏时几何搜索会偏向屏幕顶部的选集栏。
        // FocusProperties.up 是非空 FocusRequester：两者都未传时保持 Default 行为
        // （交还几何搜索），不能赋 null。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    when {
                        // 只有进度条真的可聚焦时才把「向上」路由给它：时长未知时它是
                        // focusable(enabled = false)，节点根本不存在，路由过去会让上键被静默吞掉
                        // （自定义路由失败后 Compose 不做几何兜底）→ 退回顶栏落点。
                        hasDuration && scrubFocus != null ->
                            Modifier.focusProperties { up = scrubFocus }

                        upFocus != null -> Modifier.focusProperties { up = upFocus }
                        else -> Modifier
                    }
                ),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onPrevEpisode != null) {
                ControlButton(
                    text = "⏮",
                    label = "上一集",
                    onClick = onPrevEpisode,
                )
            }

            ControlButton(
                text = "⏪",
                label = "退15秒",
                onClick = { onSeekBy(-SEEK_STEP_MS) },
            )

            ControlButton(
                text = if (isPlaying) "❚❚" else "▶",
                label = if (isPlaying) "暂停" else "播放",
                primary = true,
                onClick = onTogglePlayPause,
                modifier = if (playPauseFocus != null) {
                    Modifier.focusRequester(playPauseFocus)
                } else {
                    Modifier
                },
            )

            ControlButton(
                text = "⏩",
                label = "进15秒",
                onClick = { onSeekBy(SEEK_STEP_MS) },
            )

            if (onNextEpisode != null) {
                ControlButton(
                    text = "⏭",
                    label = "下一集",
                    onClick = onNextEpisode,
                )
            }
        }
    }
}

/**
 * 控制条按钮：图标 + 焦点时在下方追加文字标签（TV 远距离可读）。
 * 焦点态 = 放大 1.06 + 描边，与 TvButton / TvChip 同一套焦点语言。
 */
@Composable
private fun ControlButton(
    text: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val shape = TvShapes.Button
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bg = when {
        primary -> TvColors.Accent
        isFocused -> TvColors.BgHover
        else -> TvColors.BgElevated
    }
    val contentColor = if (primary) TvColors.AccentInk else TvColors.TextPrimary

    Box(
        modifier = modifier
            .graphicsLayer {
                val s = if (isFocused) 1.06f else 1f
                scaleX = s
                scaleY = s
            }
            .then(
                if (isFocused) {
                    Modifier.border(2.dp, if (primary) Color.White else TvColors.Accent, shape)
                } else {
                    Modifier.border(1.dp, TvColors.Line, shape)
                }
            )
            .background(bg, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                style = TvType.BodyLarge.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold),
                color = contentColor,
                maxLines = 1,
            )
            if (isFocused) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = label,
                    style = TvType.Caption.copy(fontSize = 12.sp),
                    color = if (primary) TvColors.AccentInk else TvColors.Accent,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 毫秒 → m:ss / h:mm:ss（与历史页格式一致）。 */
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}
