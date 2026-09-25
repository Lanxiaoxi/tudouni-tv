package com.tudouni.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.delay

/**
 * 选集网格（对应设计方案 §5.8）：
 * - 默认单元 96×64dp、圆角 12dp、gap 12dp、文本 26sp/600；可通过 [cellWidth]/[cellHeight]/[fontSize] 定制
 *   （播放页 70:30 布局下传 67×45dp + 18sp，2026-08-13）
 * - 默认：--bg-elevated + 次级字；当前集：accent 底 + 深字（800）
 * - 焦点：scale 1.08 + 白描边（当前集）/ accent 描边（普通项）—— 已选中 ≠ 焦点所在，视觉可区分
 * - 方向键网格移动；数字键 0-9 直接跳集（1-9 → 1-9 集，0 → 第 10 集）；滚动跟随
 * - [initialFocusIndex]：进入页面时滚动到该集并请求焦点（只在**首次进入**时生效；
 *   播放页传的是 currentIndex，换集不应再次抢焦点）
 */
@Composable
fun EpisodeGrid(
    count: Int,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    initialFocusIndex: Int? = null,
    cellWidth: Dp = 96.dp,
    cellHeight: Dp = 64.dp,
    fontSize: TextUnit = 26.sp,
) {
    val gridState = rememberLazyGridState()

    // 当前集变化（换源/恢复进度）时滚动到可视区
    LaunchedEffect(currentIndex) {
        if (count > 0) gridState.scrollToItem(currentIndex.coerceIn(0, count - 1))
    }

    // 进入页面：滚动到指定集并聚焦。
    // startFocusIndex 用 remember 固定成「首次进入时的值」：调用方（播放页）传的是 currentIndex，
    // 若直接把它当 LaunchedEffect 的 key，每次换集都会重跑 → 延迟后把焦点从控制条/用户所在位置
    // 硬拽回选集格，用户想连按「下一集」时按不到，按 OK 还会落在当前集上。
    // 等 item 组合改用「重试 + 捕获未初始化异常」，固定 delay 在慢设备上不一定够。
    val initialFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val startFocusIndex = remember { initialFocusIndex }
    LaunchedEffect(startFocusIndex) {
        val target = startFocusIndex ?: return@LaunchedEffect
        if (count <= 0) return@LaunchedEffect
        gridState.scrollToItem(target.coerceIn(0, count - 1))
        repeat(FOCUS_RETRY_ATTEMPTS) {
            if (runCatching { initialFocusRequester.requestFocus() }.isSuccess) {
                return@LaunchedEffect
            }
            delay(FOCUS_RETRY_MS)
        }
    }

    // 数字 → 集序号（1-based），越界忽略
    fun handleDigit(digit: Int): Boolean {
        val index = digit - 1
        if (index < count) {
            onSelect(index)
            return true
        }
        return false
    }

    // 数字键跳集：只拦截数字键（1-9 → 1-9 集，0 → 第 10 集），其余按键放行
    val digitKeyHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        if (event.type == KeyEventType.KeyDown) {
            when (event.key) {
                Key.One -> handleDigit(1)
                Key.Two -> handleDigit(2)
                Key.Three -> handleDigit(3)
                Key.Four -> handleDigit(4)
                Key.Five -> handleDigit(5)
                Key.Six -> handleDigit(6)
                Key.Seven -> handleDigit(7)
                Key.Eight -> handleDigit(8)
                Key.Nine -> handleDigit(9)
                Key.Zero -> handleDigit(10)
                else -> false
            }
        } else {
            false
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(cellWidth + 12.dp),
        state = gridState,
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent(digitKeyHandler),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed((0 until count).toList()) { index, _ ->
            EpisodeCell(
                index = index,
                isCurrent = index == currentIndex,
                onClick = { onSelect(index) },
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                fontSize = fontSize,
                modifier = if (index == startFocusIndex) Modifier.focusRequester(initialFocusRequester) else Modifier,
            )
        }
    }
}

@Composable
private fun EpisodeCell(
    index: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    cellWidth: Dp,
    cellHeight: Dp,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val shape = TvShapes.Episode
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1f,
        animationSpec = tween(
            durationMillis = if (isFocused) 250 else 150,
            easing = FastOutSlowInEasing,
        ),
        label = "episodeScale",
    )

    val bg = when {
        isCurrent -> TvColors.Accent
        else -> TvColors.BgElevated
    }
    val textColor = when {
        isCurrent -> TvColors.AccentInk
        isFocused -> Color.White
        else -> TvColors.TextSecondary
    }
    val focusBorder = if (isFocused) {
        Modifier.border(2.dp, if (isCurrent) Color.White else TvColors.Accent, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .width(cellWidth)
            .height(cellHeight)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .then(focusBorder)
            .background(bg, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = (index + 1).toString(),
            style = TvType.BodyMedium.copy(
                fontSize = fontSize,
                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 焦点请求重试间隔（毫秒）。 */
private const val FOCUS_RETRY_MS = 50L

/** 焦点请求重试次数上限（50ms × 20 ≈ 1s；LazyGrid 的 item 在测量阶段才组合，慢设备需兜底）。 */
private const val FOCUS_RETRY_ATTEMPTS = 20
