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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType

/**
 * 选集网格（对应设计方案 §5.8）：
 * - 单元 96×64dp、圆角 12dp、gap 12dp、文本 26sp/600
 * - 默认：--bg-elevated + 次级字；当前集：accent 底 + 深字（800）
 * - 焦点：scale 1.08 + 白描边（当前集）/ accent 描边（普通项）—— 已选中 ≠ 焦点所在，视觉可区分
 * - 方向键网格移动；数字键 0-9 直接跳集（1-9 → 1-9 集，0 → 第 10 集）；滚动跟随
 */
@Composable
fun EpisodeGrid(
    count: Int,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()

    // 当前集变化（换源/恢复进度）时滚动到可视区
    LaunchedEffect(currentIndex) {
        if (count > 0) gridState.scrollToItem(currentIndex.coerceIn(0, count - 1))
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
        columns = GridCells.Adaptive(108.dp),
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
            )
        }
    }
}

@Composable
private fun EpisodeCell(
    index: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
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
        modifier = Modifier
            .width(96.dp)
            .height(64.dp)
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
                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
            ),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
