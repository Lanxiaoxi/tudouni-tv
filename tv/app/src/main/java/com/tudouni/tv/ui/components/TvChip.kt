package com.tudouni.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType

/**
 * TV 筛选 Chip（对应设计方案 §5.7）：
 * - 48dp 高、全圆角、内边距 24dp、文本 24sp/600
 * - 选中：浅琥珀底 + 深字（--chip-active-bg 语义）；默认：--bg-elevated 底 + 次级字
 * - 焦点：scale 1.05 + 描边（已选中用白描边保证与浅底对比，未选中用琥珀）
 */
@Composable
fun TvChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = TvShapes.Pill
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(
            durationMillis = if (isFocused) 250 else 150,
            easing = FastOutSlowInEasing,
        ),
        label = "chipScale",
    )

    val bg = when {
        selected -> TvColors.AccentStrong
        else -> TvColors.BgElevated
    }
    val contentColor = when {
        selected -> TvColors.AccentInk
        else -> TvColors.TextSecondary
    }
    // 选中 chip 焦点用白描边（浅琥珀底上），普通 chip 用琥珀描边
    val focusBorder = if (isFocused) {
        Modifier.border(2.dp, if (selected) Color.White else TvColors.Accent, shape)
    } else if (!selected) {
        Modifier.border(1.dp, TvColors.Line, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .height(48.dp)
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
            )
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TvType.BodyMedium.copy(fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = contentColor,
        )
    }
}
