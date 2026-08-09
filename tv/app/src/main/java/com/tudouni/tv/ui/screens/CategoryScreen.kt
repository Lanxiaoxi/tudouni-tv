package com.tudouni.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tudouni.tv.data.ApiClient
import com.tudouni.tv.data.VideoItem
import com.tudouni.tv.data.errorMessage
import com.tudouni.tv.ui.components.EmptyState
import com.tudouni.tv.ui.components.FullScreenLoading
import com.tudouni.tv.ui.components.PageHorizontalPadding
import com.tudouni.tv.ui.components.PosterCard
import com.tudouni.tv.ui.components.RowCardSpacing
import com.tudouni.tv.ui.components.TvButton
import com.tudouni.tv.ui.components.TvButtonStyle
import com.tudouni.tv.ui.components.TvChip
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.launch

/** 分类选项（null = 全部），与 /api/vodlist cat 参数对齐。 */
private val CAT_OPTIONS = listOf<Pair<String?, String>>(
    null to "全部",
    "movie" to "电影",
    "series" to "剧集",
    "anime" to "动漫",
    "variety" to "综艺",
)

/**
 * 分类浏览页（对应设计方案 §6.2）：分类 chips + 海报网格 + 加载更多。
 * 数据走 /api/vodlist?cat=&pg=（镜像表毫秒级，分页 24/页）。
 * 焦点流：分类 chip → 网格首卡 → 网格内方向键 → 加载更多。
 */
@Composable
fun CategoryScreen(
    initialCat: String?,
    onOpenDetail: (VideoItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var cat by rememberSaveable(initialCat) { mutableStateOf(initialCat) }
    var items by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(1) }
    var loadingFirst by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    val gridState = rememberLazyGridState()

    suspend fun load(pageToLoad: Int, isFirst: Boolean) {
        if (isFirst) {
            loadingFirst = true
        } else {
            loadingMore = true
        }
        error = null
        try {
            val resp = ApiClient.get().vodlist(cat = cat, pg = pageToLoad)
            if (resp.isSuccessful) {
                val body = resp.body()
                val data = body?.data
                if (body != null && body.code == 0 && data != null) {
                    items = if (isFirst) data.items else items + data.items
                    total = data.total
                    page = pageToLoad
                } else {
                    error = body?.message ?: "加载失败"
                }
            } else {
                error = resp.errorMessage()
            }
        } catch (e: Exception) {
            error = "网络错误: ${e.message}"
        } finally {
            loadingFirst = false
            loadingMore = false
        }
    }

    // 切换分类 → 重载第一页
    LaunchedEffect(cat, retryKey) {
        items = emptyList()
        page = 1
        load(1, isFirst = true)
    }

    // 滚动到接近末尾 → 自动加载下一页
    val hasMore = items.size < total
    LaunchedEffect(gridState, cat, items.size, total) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last to info.totalItemsCount
        }.collect { (last, count) ->
            if (hasMore && !loadingMore && !loadingFirst && count > 0 && last >= count - 6) {
                load(page + 1, isFirst = false)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 页标题 + 结果数
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PageHorizontalPadding)
                .padding(top = 32.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(40.dp)
                    .background(TvColors.Accent)
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = CAT_OPTIONS.first { it.first == cat }.second,
                style = TvType.PageTitle,
                color = TvColors.TextPrimary,
            )
            Spacer(Modifier.width(20.dp))
            if (!loadingFirst && items.isNotEmpty()) {
                Text(
                    text = "共 $total 部",
                    style = TvType.BodyMedium,
                    color = TvColors.TextTertiary,
                )
            }
        }

        // 分类 chips（横向）
        LazyRow(
            contentPadding = PaddingValues(horizontal = PageHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(CAT_OPTIONS.size) { i ->
                val (value, label) = CAT_OPTIONS[i]
                TvChip(
                    text = label,
                    selected = cat == value,
                    onClick = { cat = value },
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        when {
            loadingFirst && items.isEmpty() -> FullScreenLoading()

            error != null && items.isEmpty() -> EmptyState(
                title = "加载失败",
                description = error,
                actionText = "重试",
                onAction = { retryKey++ },
            )

            items.isEmpty() -> EmptyState(
                title = "暂无内容",
                description = "该分类下暂时没有内容",
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(240.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = PageHorizontalPadding,
                    end = PageHorizontalPadding,
                    bottom = 48.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(RowCardSpacing),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                items(items, key = { it.vodId ?: "${it.sourceCode}_${it.hashCode()}" }) { item ->
                    PosterCard(item = item, onClick = { onOpenDetail(item) })
                }
                // 网格末尾：加载更多 / 已全部
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            loadingMore -> Text(
                                text = "加载中…",
                                style = TvType.BodyMedium,
                                color = TvColors.TextTertiary,
                            )

                            hasMore -> TvButton(
                                text = "加载更多（${items.size}/$total）",
                                style = TvButtonStyle.Secondary,
                                onClick = { scope.launch { load(page + 1, isFirst = false) } },
                            )

                            else -> Text(
                                text = "已显示全部 $total 部",
                                style = TvType.BodyMedium,
                                color = TvColors.TextTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}
