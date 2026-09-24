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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.data.ApiClient
import com.tudouni.tv.data.ContentFilter
import com.tudouni.tv.data.SearchHistoryItem
import com.tudouni.tv.data.SettingsPreference
import com.tudouni.tv.data.TvRepository
import com.tudouni.tv.data.VideoItem
import com.tudouni.tv.data.pageErrorMessage
import com.tudouni.tv.data.userFacingError
import com.tudouni.tv.ui.LocalRelogin
import com.tudouni.tv.ui.components.EmptyState
import com.tudouni.tv.ui.components.FullScreenLoading
import com.tudouni.tv.ui.components.LoadFailedState
import com.tudouni.tv.ui.components.PageHorizontalPadding
import com.tudouni.tv.ui.components.PosterCard
import com.tudouni.tv.ui.components.RowCardSpacing
import com.tudouni.tv.ui.components.TvButton
import com.tudouni.tv.ui.components.TvButtonStyle
import com.tudouni.tv.ui.components.TvChip
import com.tudouni.tv.ui.components.TvTextKeyboard
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 搜索页（对应设计方案 §6.3，H1 改造：自研 TV 键盘，不依赖系统 IME）：
 * - 输入：TvTextKeyboard（字母矩阵 + 拼音候选），方向键 + OK 全流程可操作
 * - 最近搜索：服务端 /api/search-history，chips 可聚焦点击直接搜
 * - 热门推荐：未搜索时展示 /api/items 最新内容
 * - 结果网格：/api/search 聚合多源；L2 支持滚动加载更多（分页）
 * - L3 竞态保护：连续搜索只采纳最后一次结果
 */
@Composable
fun SearchScreen(
    onOpenDetail: (VideoItem) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hotGridState = rememberLazyGridState()
    val resultGridState = rememberLazyGridState()
    val settingsPreference = remember { SettingsPreference(context) }

    var keyword by remember { mutableStateOf("") }
    // 已提交的搜索词（null = 未搜索，展示推荐区）
    var submitted by remember { mutableStateOf<String?>(null) }

    var resultItems by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var resultTotal by remember { mutableStateOf(0) }
    var resultPage by remember { mutableIntStateOf(1) }
    var loadingMore by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    // L3：搜索请求序号，只采纳最后一次
    var searchSeq by remember { mutableIntStateOf(0) }

    var hotItems by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var hotLoading by remember { mutableStateOf(true) }
    var historyItems by remember { mutableStateOf<List<SearchHistoryItem>>(emptyList()) }
    var contentFilterEnabled by remember { mutableStateOf(settingsPreference.isContentFilterEnabled()) }

    // 键盘展开/收起：搜索出结果后收起键盘，把垂直空间让给结果网格。
    // 1080p 电视上键盘约占 272dp，加上标题/输入框/间距后顶部固定区达 446dp，
    // 留给结果区不足 100dp——连一张海报（177dp）都装不下，焦点下移到海报时
    // 系统判定「已部分可见」不再滚动，表现为只能看到海报上半部分。
    // 同一根因也影响未搜索时的「热门推荐」网格，故这里做成可手动切换。
    var keyboardExpanded by remember { mutableStateOf(true) }
    // 结果网格首项的焦点（收起键盘后焦点会随键盘一起消失，需移交给结果首项）
    val firstResultFocus = remember { FocusRequester() }
    // 焦点移交的一次性标记：每次搜索复位，避免「展开/收起键盘」这类操作
    // 重新触发 effect 把焦点从用户当前位置拽回首项
    var resultFocusHandedOff by remember { mutableStateOf(false) }

    // 进入页面：并行拉热门推荐 + 最近搜索（搜索历史失败不阻塞页面）
    LaunchedEffect(Unit) {
        scope.launch {
            hotLoading = true
            try {
                val resp = ApiClient.get().items(offset = 0, limit = 24)
                val data = resp.body()?.data
                if (resp.isSuccessful && data != null) {
                    // 应用内容分级过滤
                    hotItems = if (contentFilterEnabled) {
                        ContentFilter.filterItems(data.items)
                    } else {
                        data.items
                    }
                }
            } catch (_: Exception) {
            } finally {
                hotLoading = false
            }
        }
        try {
            historyItems = TvRepository.fetchSearchHistory(20)
        } catch (_: Exception) {
            historyItems = emptyList()
        }
    }

    suspend fun runSearch(q: String, page: Int, seq: Int) {
        try {
            val resp = ApiClient.get().search(wd = q, page = page)
            if (seq != searchSeq) return
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
                    
                    if (page == 1) {
                        resultItems = filteredItems
                        resultTotal = data.total
                    } else {
                        // 分页追加（按序去重 vod_id，避免跨页重复）
                        val existing = resultItems.map { it.vodId to it.sourceCode }
                        resultItems = resultItems + filteredItems.filter { (it.vodId to it.sourceCode) !in existing }
                        resultTotal = data.total
                    }
                    resultPage = page
                    searchError = null
                } else {
                    if (page == 1) searchError = body?.message ?: "搜索失败"
                }
            } else {
                if (page == 1) searchError = resp.pageErrorMessage()
            }
        } catch (e: Exception) {
            if (page == 1) searchError = e.userFacingError()
        }
    }

    fun doSearch(wd: String) {
        val q = wd.trim()
        if (q.isEmpty()) return
        submitted = q
        keyword = q
        // 提交即收起键盘：结果区需要完整高度才装得下一张海报（见 keyboardExpanded 说明）
        keyboardExpanded = false
        // 新一轮搜索：允许再次移交焦点
        resultFocusHandedOff = false
        val seq = ++searchSeq
        scope.launch {
            searching = true
            searchError = null
            resultPage = 1
            resultItems = emptyList()
            resultTotal = 0
            runSearch(q, 1, seq)
            searching = false
            // 上报搜索历史（fire-and-forget，失败不影响结果）
            if (seq == searchSeq) {
                try {
                    TvRepository.addSearchHistory(q)
                    historyItems = TvRepository.fetchSearchHistory(20)
                } catch (_: Exception) {
                }
            }
        }
        // 新搜索回到结果网格顶部
        scope.launch { resultGridState.scrollToItem(0) }
    }

    fun loadMore() {
        if (loadingMore || searching) return
        val nextPage = resultPage + 1
        val q = submitted ?: return
        val seq = searchSeq
        scope.launch {
            loadingMore = true
            runSearch(q, nextPage, seq)
            loadingMore = false
        }
    }

    // L2：滚动到结果网格接近末尾 → 自动加载下一页
    val hasMore = resultItems.size < resultTotal
    LaunchedEffect(resultGridState, resultItems.size, resultTotal, submitted) {
        snapshotFlow {
            val info = resultGridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last to info.totalItemsCount
        }.collect { (last, count) ->
            if (hasMore && !loadingMore && !searching && count > 0 && last >= count - 6) {
                loadMore()
            }
        }
    }

    // 结果就绪（且键盘已收起）→ 把焦点交给结果首项：
    // 收起键盘会连同其上的焦点节点一起移除，不移交则焦点丢失、方向键整页失效。
    // 与 HistoryScreen 同样的重试写法：requestFocus 在目标未挂载时抛
    // IllegalStateException（慢设备上 LazyVerticalGrid 组合晚于协程执行）。
    // 用 resultFocusHandedOff 保证每次搜索只移交一次——否则用户手动收起键盘时
    // 会被重新拽回首项。
    LaunchedEffect(submitted, resultItems.isNotEmpty(), keyboardExpanded) {
        if (submitted != null && resultItems.isNotEmpty() && !keyboardExpanded && !resultFocusHandedOff) {
            var attempts = 0
            while (attempts < FOCUS_RETRY_ATTEMPTS) {
                if (runCatching { firstResultFocus.requestFocus() }.isSuccess) {
                    resultFocusHandedOff = true
                    break
                }
                delay(FOCUS_RETRY_MS)
                attempts++
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 页标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PageHorizontalPadding)
                .padding(top = 24.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(40.dp)
                    .background(TvColors.Accent),
            )
            Spacer(Modifier.width(14.dp))
            Text(text = "搜索", style = TvType.PageTitle, color = TvColors.TextPrimary)
        }

        // 输入显示区（当前输入内容大字回显）+ 右侧「完成」按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PageHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .background(TvColors.BgElevated, TvShapes.Button)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (keyword.isEmpty()) {
                    Text(
                        text = "输入片名 / 拼音搜索",
                        style = TvType.BodyLarge.copy(fontSize = 22.sp),
                        color = TvColors.TextTertiary,
                    )
                } else {
                    Text(
                        text = keyword,
                        style = TvType.BodyLarge.copy(fontSize = 26.sp),
                        color = TvColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            // 「搜索」：搜索框右侧，与搜索框同高（64dp 默认高度），输入非空才可用
            TvButton(
                text = "搜索",
                onClick = { doSearch(keyword) },
                enabled = keyword.isNotEmpty(),
                fontSize = 18.sp,
            )
            // 键盘显隐切换：收起后键盘占的 272dp 全部让给内容网格（否则一屏装不下
            // 一张海报）；需要改关键词时再展开。两种状态都保留入口，避免键盘收起后
            // 无处可点。
            Spacer(Modifier.width(14.dp))
            TvButton(
                text = if (keyboardExpanded) "收起键盘" else "展开键盘",
                style = TvButtonStyle.Secondary,
                onClick = { keyboardExpanded = !keyboardExpanded },
                fontSize = 18.sp,
            )
        }
        Spacer(Modifier.height(14.dp))

        // H1：自研 TV 键盘（初始焦点在键盘第一个键，保证方向键可用）。
        // 结果页收起键盘，把高度让给结果网格（否则结果区不足 100dp，装不下一张海报）。
        // 折叠用「不组合」而非 height(0)：隐藏的按键若仍在组合中会继续参与焦点搜索，
        // 方向键会落到看不见的按钮上。
        if (keyboardExpanded) {
            TvTextKeyboard(
                value = keyword,
                onValueChange = { keyword = it },
                onSubmit = { doSearch(keyword) },
                initialFocus = true,
                modifier = Modifier.padding(horizontal = PageHorizontalPadding),
            )
            Spacer(Modifier.height(18.dp))
        } else {
            Spacer(Modifier.height(4.dp))
        }

        Box(Modifier.fillMaxSize()) {
            if (submitted == null) {
                // ---- 未搜索：最近搜索 + 热门推荐 ----
                Column(Modifier.fillMaxSize()) {
                    if (historyItems.isNotEmpty()) {
                        Text(
                            text = "最近搜索",
                            style = TvType.RowTitle.copy(fontSize = 22.sp),
                            color = TvColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = PageHorizontalPadding),
                        )
                        Spacer(Modifier.height(12.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PageHorizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            // LazyRow 版 itemsIndexed（与 grid 版同名扩展，按接收者类型自动解析）
                            itemsIndexed(historyItems, key = { i, _ -> "h_$i" }) { _, item ->
                                TvChip(
                                    text = item.keyword ?: "",
                                    selected = false,
                                    onClick = { doSearch(item.keyword ?: "") },
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }

                    Text(
                        text = "热门推荐",
                        style = TvType.RowTitle.copy(fontSize = 22.sp),
                        color = TvColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = PageHorizontalPadding),
                    )
                    Spacer(Modifier.height(12.dp))
                    when {
                        hotLoading -> FullScreenLoading(text = "加载推荐中…")
                        hotItems.isEmpty() -> EmptyState(
                            title = "暂无推荐",
                            description = "稍后再来看看",
                        )
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(200.dp),
                            state = hotGridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = PageHorizontalPadding,
                                end = PageHorizontalPadding,
                                bottom = 48.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(RowCardSpacing),
                            verticalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                            itemsIndexed(hotItems, key = { i, _ -> "h_$i" }) { _, item ->
                                PosterCard(item = item, onClick = { onOpenDetail(item) })
                            }
                        }
                    }
                }
            } else {
                // ---- 已搜索：结果计数 + 结果网格（分页加载更多） ----
                when {
                    searching && resultItems.isEmpty() -> FullScreenLoading(text = "搜索「${submitted}」…")

                    searchError != null && resultItems.isEmpty() -> LoadFailedState(
                        message = searchError,
                        title = "搜索失败",
                        retryText = "返回重试",
                        onRetry = {
                            submitted = null
                            keyboardExpanded = true
                        },
                        onRelogin = LocalRelogin.current,
                    )

                    resultItems.isEmpty() -> EmptyState(
                        title = "未找到「${submitted}」",
                        description = "换个关键词试试，或看看热门推荐",
                        actionText = "返回热门推荐",
                        onAction = {
                            submitted = null
                            keyboardExpanded = true
                        },
                    )

                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(200.dp),
                        state = resultGridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = PageHorizontalPadding,
                            end = PageHorizontalPadding,
                            bottom = 48.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(RowCardSpacing),
                        verticalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        item(key = "result_count", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = "「${submitted}」 找到 $resultTotal 部",
                                style = TvType.BodyMedium,
                                color = TvColors.TextTertiary,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        itemsIndexed(resultItems, key = { i, _ -> "r_$i" }) { i, item ->
                            PosterCard(
                                item = item,
                                onClick = { onOpenDetail(item) },
                                focusRequester = if (i == 0) firstResultFocus else null,
                            )
                        }
                        item(key = "result_footer", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    loadingMore -> Text(
                                        text = "加载中…",
                                        style = TvType.BodyMedium,
                                        color = TvColors.TextTertiary,
                                    )
                                    !hasMore -> Text(
                                        text = "已显示全部 $resultTotal 部",
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
    }
}

/** 焦点请求重试间隔（毫秒）。 */
private const val FOCUS_RETRY_MS = 50L

/** 焦点请求重试次数上限（50ms × 20 ≈ 1s；正常情况首次即成功，仅慢设备兜底）。 */
private const val FOCUS_RETRY_ATTEMPTS = 20
