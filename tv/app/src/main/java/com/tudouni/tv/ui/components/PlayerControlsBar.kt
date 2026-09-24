package com.tudouni.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType

/** 快进/快退步长（毫秒）。 */
const val SEEK_STEP_MS = 10_000L

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
 * ────────────────────────────────────────────  ← 进度条 + 时间（纯展示，不可聚焦）
 *   [⏮ 上一集?]  [▶/❚❚]  [⏪ 退10秒]  [⏩ 进10秒]  [⏭ 下一集?]
 * ```
 * 上下集并入按钮行，是为了让导航退化为一维：左右在行内移动，上下在「按钮行 ↔ 顶栏」间移动。
 *
 * ## 焦点
 * - 仅按钮行可聚焦；进度条不可聚焦（避免「能聚焦但按 OK 无反应」的死焦点）
 * - 左右移动由 Compose 几何搜索处理（同一行内，结果确定且符合直觉）
 * - 上下方向由调用方显式指定：[upFocus] 是「向上」的落点。**必须显式指定**——
 *   非全屏布局下右侧选集栏从屏幕顶部开始，按下键时几何搜索会优先选它
 *   （Compose 加权距离 = 13×纵向距离² + 横向偏差²，纵向被放大 13 倍，
 *   选集首行纵向仅差 50dp 就压倒了底部控制条的 334dp），导致焦点跑到选集而非控制条
 * - 控制条需要「不抢焦点」时，调用方直接不组合它（而非传入不可聚焦标志）——
 *   不组合才能同时保证视觉隐藏与焦点不可达
 *
 * @param isPlaying 是否播放中（决定图标与标签）
 * @param positionMs 当前播放位置
 * @param durationMs 总时长（未知传 0：进度条按 0 显示、时长显示 --:--）
 * @param onTogglePlayPause 播放/暂停
 * @param onSeekBy 相对跳转（正数快进、负数快退），单位毫秒
 * @param onPrevEpisode 上一集；null 表示当前已是第一集（按钮不出现）
 * @param onNextEpisode 下一集；null 表示当前已是最后一集（按钮不出现）
 * @param playPauseFocus 播放/暂停按钮的焦点锚点。**始终传入**（非全屏也要传）：
 *   顶部按钮的「向下」路由指向它，未挂载的 FocusRequester 无法作为路由目标
 * @param upFocus 所有按钮「向上」的落点（通常指向顶部「返回」按钮）
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
) {
    val hasDuration = durationMs > 0
    val progress = if (hasDuration) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

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
        // ---- 进度条 + 时间（纯展示，不可聚焦） ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(TvColors.BgElevated, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(8.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(TvColors.Accent, TvColors.AccentStrong),
                            ),
                            RoundedCornerShape(4.dp),
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

        // ---- 按钮行（唯一可聚焦区） ----
        // 按钮行的「向上」统一路由到顶栏（upFocus）：
        // 非全屏时右侧选集栏从屏幕顶部开始，几何搜索会优先选它而非底部控制条，
        // 必须显式指定落点。左右仍走几何搜索（同一行内结果符合直觉）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (upFocus != null) {
                        Modifier.focusProperties { up = upFocus }
                    } else {
                        Modifier
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
                text = "⏪",
                label = "退10秒",
                onClick = { onSeekBy(-SEEK_STEP_MS) },
            )

            ControlButton(
                text = "⏩",
                label = "进10秒",
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
