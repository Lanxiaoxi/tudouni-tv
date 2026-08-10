package com.tudouni.tv.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType

/** 全屏加载：中央 spin（琥珀）+ 文案（对应 Web #loading）。 */
@Composable
fun FullScreenLoading(text: String = "加载中…") {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = TvColors.Accent,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = text,
                style = TvType.BodyMedium,
                color = TvColors.TextSecondary,
            )
        }
    }
}

/** 骨架海报位：灰块 + 呼吸闪烁 1.2s（对应 §5.10，禁止焦点）。 */
@Composable
fun SkeletonPoster(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Box(
        modifier = modifier
            .alpha(alpha)
            .background(TvColors.BgElevated, TvShapes.Card),
    )
}

/** 空状态（对应 §5.11）：图标 + 标题 + 说明 + 必带可聚焦操作按钮。 */
@Composable
fun EmptyState(
    title: String,
    description: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(TvColors.BgElevated.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "◎",
                style = TvType.DisplayTitle,
                color = TvColors.TextTertiary.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(text = title, style = TvType.PageTitle.copy(fontSize = 26.sp), color = TvColors.TextPrimary)
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = description,
                style = TvType.BodyMedium,
                color = TvColors.TextTertiary,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(32.dp))
            TvButton(text = actionText, onClick = onAction)
        }
    }
}
