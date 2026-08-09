package com.tudouni.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tudouni.tv.data.VideoItem
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvType

/** 页面左右安全边距（对标主流 TV app，56dp）。 */
val PageHorizontalPadding = 56.dp

/** 内容行内卡片间距。 */
val RowCardSpacing = 16.dp

/**
 * 内容行（对应设计方案 §5.3）：行标题（琥珀竖条 + 34sp/800，不可聚焦）+ 横向 PosterCard 列表。
 * LazyRow 系统焦点滚动跟随；左右不循环（到行尾停在原处）。
 */
@Composable
fun ContentRow(
    title: String,
    items: List<VideoItem>,
    onClickItem: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    showMore: Boolean = false,
    onMore: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = PageHorizontalPadding),
) {
    if (items.isEmpty()) return
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = PageHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 琥珀竖条（对应 Web .mark）
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(34.dp)
                    .background(TvColors.Accent),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                style = TvType.RowTitle,
                color = TvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showMore && onMore != null) {
                Spacer(Modifier.weight(1f))
                TvButton(
                    text = "更多 →",
                    style = TvButtonStyle.Ghost,
                    onClick = onMore,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        LazyRow(
            state = listState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(RowCardSpacing),
        ) {
            items(items, key = { it.vodId ?: "${it.sourceCode}_${it.hashCode()}" }) { item ->
                PosterCard(item = item, onClick = { onClickItem(item) })
            }
        }
    }
}
