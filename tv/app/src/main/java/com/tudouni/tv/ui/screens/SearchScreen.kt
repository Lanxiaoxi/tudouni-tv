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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.data.ApiClient
import com.tudouni.tv.data.SearchHistoryItem
import com.tudouni.tv.data.TvRepository
import com.tudouni.tv.data.VideoItem
import com.tudouni.tv.data.errorMessage
import com.tudouni.tv.ui.components.ContentRow
import com.tudouni.tv.ui.components.EmptyState
import com.tudouni.tv.ui.components.FullScreenLoading
import com.tudouni.tv.ui.components.PageHorizontalPadding
import com.tudouni.tv.ui.components.PosterCard
import com.tudouni.tv.ui.components.RowCardSpacing
import com.tudouni.tv.ui.components.TvChip
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.launch

/**
 * 搜索页（对应设计方案 §6.3）：
 * - TV 键盘：输入框聚焦自动弹系统 IME，OK/Search 键提交
 * - 最近搜索：服务端 /api/search-history，chips 可聚焦点击直接搜
 * - 热门推荐：未搜索时展示 /api/items 最新内容（无热搜接口，用最新内容兜底）
 * - 结果网格：/api/search 聚合多源
 */
@Composable
fun SearchScreen(
    onOpenDetail: (VideoItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val inputFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    var keyword by remember { mutableStateOf("") }
    // 已提交的搜索词（null = 未搜索，展示推荐区）
    var submitted by remember { mutableStateOf<String?>(null) }

    var resultItems by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    var hotItems by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var hotLoading by remember { mutableStateOf(true) }
    var historyItems by remember { mutableStateOf<List<SearchHistoryItem>>(emptyList()) }

    // 进入页面：焦点落到搜索框，弹软键盘；并行拉热门推荐 + 最近搜索
    LaunchedEffect(Unit) {
        inputFocusRequester.requestFocus()
        scope.launch {
            hotLoading = true
            try {
                val resp = ApiClient.get().items(offset = 0, limit = 24)
                val data = resp.body()?.data
                if (resp.isSuccessful && data != null) hotItems = data.items
            } catch (_: Exception) {
            } finally {
                hotLoading = false
            }
        }
        historyItems = TvRepository.fetchSearchHistory(20)
    }

    fun doSearch(wd: String) {
        val q = wd.trim()
        if (q.isEmpty()) return
        submitted = q
        keyword = q
        focusManager.clearFocus()
        scope.launch {
            searching = true
            searchError = null
            try {
                val resp = ApiClient.get().search(wd = q, page = 1)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    val data = body?.data
                    if (body != null && body.code == 0 && data != null) {
                        resultItems = data.items
                        total = data.total
                    } else {
                        searchError = body?.message ?: "搜索失败"
                    }
                } else {
                    searchError = resp.errorMessage()
                }
            } catch (e: Exception) {
                searchError = "网络错误: ${e.message}"
            } finally {
                searching = false
            }
            // 上报搜索历史（fire-and-forget，失败不影响结果）
            TvRepository.addSearchHistory(q)
            historyItems = TvRepository.fetchSearchHistory(20)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 页标题
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
                    .background(TvColors.Accent),
            )
            Spacer(Modifier.width(14.dp))
            Text(text = "搜索", style = TvType.PageTitle, color = TvColors.TextPrimary)
        }

        // 搜索框（56dp 高，accent 聚焦描边）
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            placeholder = { Text("输入片名搜索", style = TvType.BodyMedium) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch(keyword) }),
            shape = RoundedCornerShape(14.dp),
            textStyle = TvType.BodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PageHorizontalPadding)
                .height(64.dp)
                .focusRequester(inputFocusRequester),
        )
        Spacer(Modifier.height(20.dp))

        if (submitted == null) {
            // ---- 未搜索：最近搜索 + 热门推荐 ----
            if (historyItems.isNotEmpty()) {
                Text(
                    text = "最近搜索",
                    style = TvType.RowTitle.copy(fontSize = 22.sp),
                    color = TvColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = PageHorizontalPadding),
                )
                Spacer(Modifier.height(14.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = PageHorizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(historyItems.size) { i ->
                        val item = historyItems[i]
                        TvChip(
                            text = item.keyword ?: "",
                            selected = false,
                            onClick = { doSearch(item.keyword ?: "") },
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            }

            Text(
                text = "热门推荐",
                style = TvType.RowTitle.copy(fontSize = 22.sp),
                color = TvColors.TextSecondary,
                modifier = Modifier.padding(horizontal = PageHorizontalPadding),
            )
            Spacer(Modifier.height(14.dp))
            when {
                hotLoading -> FullScreenLoading(text = "加载推荐中…")
                hotItems.isEmpty() -> EmptyState(
                    title = "暂无推荐",
                    description = "稍后再来看看",
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
                    items(hotItems, key = { it.vodId ?: "${it.sourceCode}_${it.hashCode()}" }) { item ->
                        PosterCard(item = item, onClick = { onOpenDetail(item) })
                    }
                }
            }
        } else {
            // ---- 已搜索：结果计数 + 结果网格 ----
            when {
                searching && resultItems.isEmpty() -> FullScreenLoading(text = "搜索「${submitted}」…")

                searchError != null && resultItems.isEmpty() -> EmptyState(
                    title = "搜索失败",
                    description = searchError,
                    actionText = "返回重试",
                    onAction = { submitted = null },
                )

                resultItems.isEmpty() -> EmptyState(
                    title = "未找到「${submitted}」",
                    description = "换个关键词试试，或看看热门推荐",
                    actionText = "返回热门推荐",
                    onAction = { submitted = null },
                )

                else -> {
                    Row(
                        modifier = Modifier.padding(horizontal = PageHorizontalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "「${submitted}」 找到 $total 部",
                            style = TvType.BodyMedium,
                            color = TvColors.TextTertiary,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    LazyVerticalGrid(
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
                        items(resultItems, key = { it.vodId ?: "${it.sourceCode}_${it.hashCode()}" }) { item ->
                            PosterCard(item = item, onClick = { onOpenDetail(item) })
                        }
                    }
                }
            }
        }
    }
}
