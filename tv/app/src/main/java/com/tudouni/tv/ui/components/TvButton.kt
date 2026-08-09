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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType

/** 按钮风格：Primary=琥珀底+深字（焦点白描边）；Secondary=浅底；Ghost=透明底。 */
enum class TvButtonStyle { Primary, Secondary, Ghost }

/**
 * TV 按钮（对应设计方案 §5.6）：
 * - 高度 64dp、圆角 14dp、按钮文字 26sp/700
 * - 焦点态 scale 1.05 + 描边：Primary 用白色（琥珀底上保证对比），其余用琥珀色
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TvButtonStyle = TvButtonStyle.Primary,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    fontSize: TextUnit = 24.sp,
) {
    val shape = TvShapes.Button
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(
            durationMillis = if (isFocused) 250 else 150,
            easing = FastOutSlowInEasing,
        ),
        label = "buttonScale",
    )

    val bg = when (style) {
        TvButtonStyle.Primary -> if (enabled) TvColors.Accent else TvColors.BgElevated
        TvButtonStyle.Secondary -> TvColors.BgElevated
        TvButtonStyle.Ghost -> Color.Transparent
    }
    val contentColor = when (style) {
        TvButtonStyle.Primary -> TvColors.AccentInk
        TvButtonStyle.Secondary -> TvColors.TextSecondary
        TvButtonStyle.Ghost -> TvColors.TextSecondary
    }
    // 主按钮（琥珀底）焦点框用白色；disabled 不描边
    val focusBorderColor = when {
        !enabled -> null
        style == TvButtonStyle.Primary -> Color.White
        else -> TvColors.Accent
    }

    val borderModifier = if (isFocused && focusBorderColor != null) {
        Modifier.border(2.dp, focusBorderColor, shape)
    } else if (style != TvButtonStyle.Ghost) {
        Modifier.border(1.dp, TvColors.Line, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .height(64.dp)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .then(borderModifier)
            .background(bg, shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leadingIcon?.let {
                it()
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = text,
                style = TvType.ButtonLabel.copy(fontSize = fontSize),
                color = if (enabled) contentColor else TvColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
