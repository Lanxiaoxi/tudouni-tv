package com.tudouni.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.ui.navigation.NavPage
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType

/**
 * 左侧导航栏（对应设计方案 §5.4）：
 * - 宽 216dp，深色面（--bg-surface 半透明 + 玻璃感）
 * - 品牌（琥珀 TV 字）+ 8 个导航项（52dp 高，图标 28dp + 文本 26sp）
 * - 焦点：整行 --bg-hover 圆角 + 文本高亮；选中页：琥珀左竖条 + 文本 800
 * - 上下移动，OK 切换页面
 */
@Composable
fun TvNavRail(
    currentPage: NavPage,
    onSelect: (NavPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(216.dp)
            .fillMaxHeight()
            .background(TvColors.BgSurface.copy(alpha = 0.92f))
            .padding(vertical = 28.dp),
    ) {
        // 品牌
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(40.dp)
                    .background(TvColors.Accent),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "土豆TV",
                style = TvType.RowTitle.copy(fontSize = 40.sp, fontWeight = FontWeight.Black),
                color = TvColors.Accent,
            )
        }
        Spacer(Modifier.height(32.dp))

        NavPage.entries.forEach { page ->
            NavItem(
                page = page,
                selected = page == currentPage,
                onClick = { onSelect(page) },
            )
        }
    }
}

@Composable
private fun NavItem(
    page: NavPage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(
            durationMillis = if (isFocused) 250 else 150,
            easing = FastOutSlowInEasing,
        ),
        label = "navScale",
    )

    val bg = when {
        isFocused -> TvColors.BgHover
        selected -> TvColors.BgElevated
        else -> Color.Transparent
    }
    val textColor = when {
        selected -> TvColors.TextPrimary
        isFocused -> TvColors.TextPrimary
        else -> TvColors.TextSecondary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                }
                .fillMaxSize()
                .background(bg, TvShapes.NavItem)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            // 选中页琥珀左条
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(4.dp)
                        .height(28.dp)
                        .background(TvColors.Accent),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = page.icon,
                    style = TvType.BodyLarge.copy(fontSize = 26.sp),
                    color = if (selected) TvColors.Accent else textColor,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = page.label,
                    style = TvType.BodyMedium.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = textColor,
                )
            }
        }
    }
}
