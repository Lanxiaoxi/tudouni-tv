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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tudouni.tv.data.ApiClient
import com.tudouni.tv.data.CategoryCache
import com.tudouni.tv.data.ContentFilter
import com.tudouni.tv.data.SettingsPreference
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

/** cat 参数 → 中文名（与 /api/vodlist cat 参数对齐）。 */
private val CAT_LABELS = mapOf(
    "movie" to "电影",
    "series" to "剧集",
    "anime" to "动漫",
    "variety" to "综艺",
)

/**
 * 分类浏览页：左侧导航已选一级分类（电影/剧集/动漫/综艺），
 * 页内 chips 展示该分类下的**二级细分类型**（从已加载数据的 type_name 聚合，如
 * 电影 → 动作片/喜剧片/爱情片…），选中后本地过滤。数据走 /api/vodlist?cat=&pg=。
 * 焦点流：细分 chip → 网格首卡 → 网格内方向键 → 加载更多。
 */
@Composable
fun CategoryScreen(
    initialCat: String?,
    onOpenDetail: (VideoItem) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsPreference = remember { SettingsPreference(context) }
    var cat by rememberSaveable(initialCat) { mutableStateOf(initialCat) }
    var items by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(1) }
    var loadingFirst by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    var contentFilterEnabled by remember { mutableStateOf(settingsPreference.isContentFilterEnabled()) }
    val gridState = rememberLazyGridState()

    // 二级细分类型（从已加载数据聚合，按出现频次取 top 10）+ 当前选中的细分
    var subCats by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedSub by remember { mutableStateOf<String?>(null) }

    // 初始焦点：加载完成后给第一个 chip（「全部」），否则整页方向键不工作
    val firstChipFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(loadingFirst) {
        if (!loadingFirst) firstChipFocus.requestFocus()
    }

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
                    // 应用内容分级过滤
                    val filteredItems = if (contentFilterEnabled) {
                        ContentFilter.filterItems(data.items)
                    } else {
                        data.items
                    }
                    
                    if (isFirst) {
                        items = filteredItems
                        total = data.total
                        page = 1
                        // 聚合二级细分
                        val typeNameFreq = mutableMapOf<String, Int>()
                        for (item in items) {
                            val type = item.typeName ?: continue
                            typeNameFreq[type] = (typeNameFreq[type] ?: 0) + 1
                        }
                        subCats = typeNameFreq.entries
                            .sortedByDescending { it.value }
                            .take(10)
                            .map { it.key }
                        selectedSub = null
                    } else {
                        val existing = items.map { it.vodId to it.sourceCode }
                        items = items + filteredItems.filter { (it.vodId to it.sourceCode) !in existing }
                        total = data.total
                        page = pageToLoad
                    }
                    // 加载成功写入缓存（TTL 100 分钟，切页/返回直接命中）
                    CategoryCache.put(cat ?: "", contentFilterEnabled, items, total, page)
                } else {
                    error = body?.message ?: "加载失败"
                }
            } else {
                error = resp.errorMessage()
            }
        } catch (e: Exception) {
            error = "网络错误: ${e.message}"
        } finally {
            if (isFirst) loadingFirst = false else loadingMore = false
        }
    }

    // 切换分类 → 优先读缓存（TTL 100 分钟），命中直接渲染不请求；未命中才加载第一页
    LaunchedEffect(cat, retryKey) {
        val cached = CategoryCache.get(cat ?: "", contentFilterEnabled)
        if (cached != null) {
            items = cached.first
            total = cached.second
            page = cached.third
            loadingFirst = false
            error = null
            selectedSub = null
        } else {
            items = emptyList()
            selectedSub = null
            page = 1
            load(1, isFirst = true)
        }
    }

    // 已加载数据变化 → 重新聚合细分类型
    LaunchedEffect(items) {
        val freq = linkedMapOf<String, Int>()
        items.forEach { item ->
            val t = item.typeName?.trim()
            if (!t.isNullOrBlank()) freq[t] = (freq[t] ?: 0) + 1
        }
        subCats = freq.entries.sortedByDescending { it.value }.take(10).map { it.key }
    }

    // 当前显示列表（选中细分时本地过滤）
    val displayItems = if (selectedSub == null) items else items.filter { it.typeName == selectedSub }

    // 滚动到接近末尾 → 自动加载下一页
    // M6 修复：count 是「当前网格可见项总数」，细分过滤后可能远小于 items.size，
    // 原条件 last >= count-6 在过滤后恒真 → 疯狂拉页。要求网格项数 >= 7 才自动加载，
    // 过滤后项少时靠底部「加载更多」按钮手动翻页。
    val hasMore = items.size < total
    LaunchedEffect(gridState, cat, items.size, total) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last to info.totalItemsCount
        }.collect { (last, count) ->
            if (hasMore && !loadingMore && !loadingFirst && count >= 7 && last >= count - 6) {
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
                text = CAT_LABELS[cat] ?: "全部",
                style = TvType.PageTitle,
                color = TvColors.TextPrimary,
            )
            Spacer(Modifier.width(20.dp))
            if (!loadingFirst && displayItems.isNotEmpty()) {
                Text(
                    text = "共 $total 部",
                    style = TvType.BodyMedium,
                    color = TvColors.TextTertiary,
                )
            }
        }

        // 二级细分 chips（全部 + 该分类下的细分类型）
        LazyRow(
            contentPadding = PaddingValues(horizontal = PageHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "sub_all") {
                TvChip(
                    text = "全部",
                    selected = selectedSub == null,
                    onClick = { selectedSub = null },
                    modifier = Modifier.focusRequester(firstChipFocus),
                )
            }
            items(subCats.size, key = { "sub_$it" }) { i ->
                val label = subCats[i]
                TvChip(
                    text = label,
                    selected = selectedSub == label,
                    onClick = { selectedSub = label },
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
                columns = GridCells.Adaptive(140.dp),
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
                // L1：用 index 做稳定 key（vodId 可能为空，hashCode 可能碰撞导致 Lazy key 冲突崩溃）
                itemsIndexed(displayItems, key = { i, _ -> "cat_$i" }) { _, item ->
                    PosterCard(item = item, onClick = { onOpenDetail(item) })
                }
                // 网格末尾：加载更多 / 已全部
                // 选中细分时隐藏「加载更多」——加载更多拉取的是「全部」下一页，
                // 新页未必含当前细分，点了条目不增反而困惑；提示切回「全部」。
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

                            selectedSub != null -> Text(
                                text = "「$selectedSub」已显示 ${displayItems.size} 条 · 切回「全部」可浏览更多",
                                style = TvType.BodyMedium,
                                color = TvColors.TextTertiary,
                            )

                            hasMore -> TvButton(
                                text = "加载更多（${items.size}/$total）",
                                style = TvButtonStyle.Secondary,
                                // 防重复点击：loadingMore 时忽略
                                onClick = {
                                    if (!loadingMore && !loadingFirst) {
                                        scope.launch { load(page + 1, isFirst = false) }
                                    }
                                },
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
