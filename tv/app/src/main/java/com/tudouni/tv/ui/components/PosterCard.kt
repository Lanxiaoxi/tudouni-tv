package com.tudouni.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tudouni.tv.data.VideoItem
import com.tudouni.tv.data.resolveMediaUrl
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType

/**
 * 海报卡（核心内容单元，对应设计方案 §5.2）：
 * - 220×330dp（2:3），圆角 16dp；焦点放大 1.08 + 琥珀描边 + 阴影
 * - 封面占满，底部渐变遮罩，左上角标（更新/高清），左下评分 ★ 8.5
 * - 焦点态中央浮现播放浮层（对应 Web .poster .play-hint）
 * - 下方：片名（2 行截断 28sp）+ 副信息（来源/年份 24sp）
 */
@Composable
fun PosterCard(
    item: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.width(118.dp)) {
        FocusableSurface(
            onClick = onClick,
            shape = TvShapes.Card,
            scale = 1.08f,
            modifier = Modifier
                .width(118.dp)
                .aspectRatio(2f / 3f),
        ) { focused ->
            Box(Modifier.fillMaxSize()) {
                // U4：占位/失败态用底色，避免加载中与加载失败显示空白
                AsyncImage(
                    model = resolveMediaUrl(item.pic),
                    contentDescription = item.vodName ?: "海报",
                    contentScale = ContentScale.Crop,
                    placeholder = ColorPainter(TvColors.BgElevated),
                    error = ColorPainter(TvColors.BgElevated),
                    modifier = Modifier.fillMaxSize(),
                )
                // 左下评分/角标（优先显示评分，无评分显示 remarks 角标）
                // 注：小海报不叠加底部渐变遮罩（曾因 startY 固定 400px 超出高度把整卡蒙暗）
                val badge = item.remarks
                if (!badge.isNullOrBlank()) {
                    val isScore = badge.contains("★") || badge.contains("评分") || badge.contains("分")
                    if (isScore) {
                        Text(
                            text = badge,
                            style = TvType.Caption.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                            color = TvColors.Score,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 8.dp, bottom = 6.dp),
                        )
                    } else {
                        Text(
                            text = badge.take(6),
                            style = TvType.Caption.copy(fontSize = 14.sp),
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .background(TvColors.Accent.copy(alpha = 0.9f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                // 焦点态播放浮层（U3：淡入 + 缩放，避免突然出现/消失）
                androidx.compose.animation.AnimatedVisibility(
                    visible = focused,
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)) +
                        androidx.compose.animation.scaleIn(initialScale = 0.8f, animationSpec = androidx.compose.animation.core.tween(150)),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) +
                        androidx.compose.animation.scaleOut(targetScale = 0.8f, animationSpec = androidx.compose.animation.core.tween(120)),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    PlayOverlay()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.vodName ?: "",
            style = TvType.PosterTitle.copy(fontSize = 18.sp),
            color = TvColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(2.dp))
        val sub = listOfNotNull(item.sourceName, item.year).joinToString(" · ")
        if (sub.isNotBlank()) {
            Text(
                text = sub,
                // 副信息（来源·年份）约为片名（18sp）一半，取 12sp 保可读下限
                style = TvType.Caption.copy(fontSize = 12.sp),
                color = TvColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** 焦点态中央播放浮层：半透明圆底 + ▶（对应 Web .poster .play-hint）。 */
@Composable
fun PlayOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(TvColors.PlayOverlay),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "▶",
            style = TvType.DisplayTitle.copy(fontSize = 28.sp),
            color = Color.White,
        )
    }
}
