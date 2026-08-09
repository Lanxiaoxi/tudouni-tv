package com.tudouni.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes

/**
 * 全站统一焦点基元（对应设计方案 §5.1）。
 *
 * 焦点态 = 放大 + 2dp 描边 + 阴影 三重反馈：
 * - 缩放 1.0 → [scale]（默认海报 1.08 / 按钮 chip 1.05），时长 250ms in / 150ms out，FastOutSlowIn
 * - 描边 [borderColor]（主按钮底色场景传 [focusedBorderColor] 白色保证对比）
 * - 阴影 0 → 8dp（0x80000000）
 *
 * 用 graphicsLayer 做缩放：只走绘制层，不触发 measure/recompose，焦点动画不引起布局抖动。
 * clickable(indication = null)：D-pad 方向键移动焦点、OK/ENTER 触发 onClick，与系统焦点算法原生兼容。
 * [content] 接收当前焦点态，便于内层叠加焦点专属浮层（如海报卡播放图标）。
 */
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = TvShapes.Card,
    scale: Float = 1.08f,
    borderColor: Color = TvColors.Accent,
    focusedBorderColor: Color? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) scale else 1f,
        animationSpec = tween(
            durationMillis = if (isFocused) 250 else 150,
            easing = if (isFocused) FastOutSlowInEasing else LinearOutSlowInEasing,
        ),
        label = "focusScale",
    )

    val borderModifier = if (isFocused) {
        Modifier.border(2.dp, focusedBorderColor ?: borderColor, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .shadow(if (isFocused) 8.dp else 0.dp, shape, clip = false)
            .then(borderModifier)
            .clip(shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        content = { content(isFocused) },
    )
}

/** 无点击的纯焦点容器（如需要焦点但不响应 OK 的占位），一般用不上，保留备用。 */
@Composable
fun FocusIndicator(
    isFocused: Boolean,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(if (isFocused) 250 else 150, easing = FastOutSlowInEasing),
        label = "focusIndicatorScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .shadow(if (isFocused) 8.dp else 0.dp, shape, clip = false)
            .then(
                if (isFocused) Modifier.border(2.dp, TvColors.Accent, shape) else Modifier
            ),
        content = content,
    )
}
