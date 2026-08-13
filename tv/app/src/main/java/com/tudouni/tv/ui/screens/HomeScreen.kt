package com.tudouni.tv.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tudouni.tv.data.ApiClient
import com.tudouni.tv.data.ContentFilter
import com.tudouni.tv.data.DetailResponse
import com.tudouni.tv.data.HomePrefetch
import com.tudouni.tv.data.SettingsPreference
import com.tudouni.tv.data.VideoItem
import com.tudouni.tv.data.errorMessage
import com.tudouni.tv.data.resolveMediaUrl
import com.tudouni.tv.ui.components.ContentRow
import com.tudouni.tv.ui.components.EmptyState
import com.tudouni.tv.ui.components.FullScreenLoading
import com.tudouni.tv.ui.components.TvButton
import com.tudouni.tv.ui.components.TvButtonStyle
import com.tudouni.tv.ui.navigation.NavPage
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页（对应设计方案 §6.1）：Hero 横幅 + 多内容行（按 type_name 客户端分组）。
 * 数据源 /api/items（镜像表毫秒级）：
 * - 首屏加载 offset=0, limit=500 快速响应
 * - 后台异步补齐 offset=500, limit=500 等后续批次（不阻塞首屏）
 * - 数据补齐时自动触发分组重算（分类页自动更新）
 */
@Composable
fun HomeScreen(
    username: String,
    onOpenDetail: (VideoItem) -> Unit,
    onPlay: (VideoItem, String, List<String>, Int, Long) -> Unit,
    onOpenCategory: (NavPage) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val settingsPreference = remember { SettingsPreference(context) }

    // 开屏预拉缓存：App Loading 期间已后台拉好首批数据 → 进入首页直接渲染内容，
    // 不再显示加载画面（仅首次组合消费一次；无缓存则走正常加载）
    val preloaded = remember { HomePrefetch.consume() }
    var items by remember { mutableStateOf(preloaded.first) }
    var totalCount by remember { mutableStateOf(preloaded.second) }  // 后端返回的全局总数
    var loading by remember { mutableStateOf(preloaded.first.isEmpty()) }
    var prefetching by remember { mutableStateOf(false) }  // 后台补齐状态
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    // 爱奇艺热播榜（独立拉取，失败静默隐藏该行）
    var iqiyiItems by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    // Hero「立即播放」需要先拉详情拿第一集地址
    var heroPlaying by remember { mutableStateOf(false) }
    var contentFilterEnabled by remember { mutableStateOf(settingsPreference.isContentFilterEnabled()) }

    // 初始焦点：Compose 不会自动聚焦第一个控件，必须显式请求，
    // 否则整页方向键焦点导航不工作。加载完成后把焦点给 Hero「立即播放」。
    val heroPlayFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(loading, items.isEmpty()) {
        if (!loading && items.isNotEmpty()) {
            heroPlayFocus.requestFocus()
        }
    }

    // Hero 轮换：取 items 前 5 个（沿用 firstOrNull 的排序，只是数量变多），自动定时切换
    val heroCandidates = items.take(5)
    var heroIndex by remember { mutableIntStateOf(0) }
    // 手动切换指示器时 +1，重启自动轮换计时（避免刚手动选完立刻被自动切走）
    var heroRotateTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(heroCandidates.size, heroRotateTick) {
        if (heroCandidates.size > 1) {
            while (true) {
                delay(HERO_ROTATE_INTERVAL_MS)
                heroIndex = (heroIndex + 1) % heroCandidates.size
            }
        }
    }
    // heroIndex 越界保护（items 后台补齐/过滤后数量可能变化；空列表时取 0 避免 coerceIn 空区间异常）
    val safeHeroIndex = heroIndex.coerceIn(0, (heroCandidates.size - 1).coerceAtLeast(0))
    // 记录用户焦点是否在 Hero 区域（切换动画重建内容会丢焦点，动画结束后需恢复）
    var heroFocused by remember { mutableStateOf(false) }
    val latestHeroFocused by rememberUpdatedState(heroFocused)
    LaunchedEffect(safeHeroIndex) {
        if (latestHeroFocused) {
            delay(HERO_TRANSITION_ANIM_MS + 100) // 等动画结束、新内容组合完成
            runCatching { heroPlayFocus.requestFocus() }
        }
    }

    LaunchedEffect(retryKey) {
        prefetching = false
        error = null
        // 无预拉数据（含重试/冷启动未命中缓存）才做首屏拉取；预拉成功则跳过，直接渲染 + 后台补齐
        if (items.isEmpty()) {
            loading = true
            totalCount = 0
            try {
                // 首屏：拉取第一批 500 条
                val resp = ApiClient.get().items(offset = 0, limit = 500)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    val data = body?.data
                    if (body != null && body.code == 0 && data != null) {
                        // 应用内容分级过滤
                        var filteredItems = data.items
                        if (contentFilterEnabled) {
                            filteredItems = ContentFilter.filterItems(filteredItems)
                        }

                        items = filteredItems
                        totalCount = data.total
                    } else {
                        error = body?.message ?: "加载失败"
                    }
                } else {
                    error = resp.errorMessage()
                }
            } catch (e: Exception) {
                error = "网络错误: ${e.message}"
            } finally {
                loading = false
            }
        }
        // 后台补齐剩余批次（预拉或首屏拉取后统一走：total > items.size）
        if (error == null && totalCount > items.size) {
            scope.launch {
                prefetchHomeItems(items.size, totalCount, contentFilterEnabled) { newItems ->
                    // 合并新数据并去重
                    val seen = items.map { it.vodId to it.sourceCode }.toSet()
                    val fresh = newItems.filter { (it.vodId to it.sourceCode) !in seen }
                    if (fresh.isNotEmpty()) {
                        items = items + fresh
                        // 记录日志
                        android.util.Log.d("HomeScreen", "后台补齐: ${items.size} / $totalCount")
                    }
                }
                prefetching = false
            }
            prefetching = true
        }
    }

    // 爱奇艺热播榜：独立拉取（与主列表解耦），失败静默——行隐藏不影响首页其他内容
    LaunchedEffect(retryKey) {
        try {
            val resp = ApiClient.get().iqiyiHot()
            if (resp.isSuccessful) {
                val body = resp.body()
                val data = body?.data
                if (body != null && body.code == 0 && data != null) {
                    var list = data.items
                    if (contentFilterEnabled) {
                        list = ContentFilter.filterItems(list)
                    }
                    iqiyiItems = list
                }
            }
        } catch (e: Exception) {
            // 静默：爱奇艺榜单失败不影响首页主内容
        }
    }

    // 客户端分组（与后端 classify_type 同逻辑）；L7：remember 缓存，避免每帧重组重算
    // 当 items 更新时自动重组（后台补齐时触发）
    val groups = remember(items) {
        HomeGroups(
            latest = items.take(12),
            movies = items.filter { classifyType(it.typeName) == "movie" }.take(8),
            series = items.filter { classifyType(it.typeName) == "series" }.take(8),
            anime = items.filter { classifyType(it.typeName) == "anime" }.take(8),
            variety = items.filter { classifyType(it.typeName) == "variety" }.take(8),
        )
    }

    fun playFirst(item: VideoItem) {
        if (heroPlaying) return
        scope.launch {
            heroPlaying = true
            try {
                val resp = ApiClient.get().detail(id = item.vodId ?: "", source = item.sourceCode)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    val eps = body?.episodes ?: emptyList()
                    val url = eps.firstOrNull()
                    if (body != null && (body.code == 200 || body.code == 0) && url != null) {
                        onPlay(item, url, eps, 0, 0L)
                    } else {
                        // 无直连地址时退回详情页
                        onOpenDetail(item)
                    }
                } else {
                    onOpenDetail(item)
                }
            } catch (e: Exception) {
                onOpenDetail(item)
            } finally {
                heroPlaying = false
            }
        }
    }

    when {
        // 兜底：无预拉缓存且加载失败前（冷启动预拉未命中时）显示普通加载转圈
        loading && items.isEmpty() -> FullScreenLoading()

        error != null && items.isEmpty() -> EmptyState(
            title = "加载失败",
            description = error,
            actionText = "重试",
            onAction = { retryKey++ },
        )

        else -> {
            // 轮换：取前 5 个，当前显示 safeHeroIndex 对应的项
            val hero = heroCandidates.getOrNull(safeHeroIndex)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 顶部欢迎区
                item(key = "header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 72.dp, vertical = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "发现",
                                style = TvType.PageTitle,
                                color = TvColors.TextPrimary,
                            )
                            if (username.isNotBlank()) {
                                Text(
                                    text = "欢迎回来，$username",
                                    style = TvType.BodyMedium,
                                    color = TvColors.TextTertiary,
                                )
                            }
                        }
                    }
                }

                // Hero 横幅（轮换：5 个候选，指示器在卡片正下方居中）
                if (hero != null) {
                    item(key = "hero") {
                        Column {
                            // 焦点跟踪：Hero 区域是否持有焦点（切换动画重建内容后需恢复焦点）
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { heroFocused = it.hasFocus },
                            ) {
                                // 轮换过渡：新 Hero 淡入 + 轻微放大，旧 Hero 淡出（400ms）
                                AnimatedContent(
                                    targetState = safeHeroIndex,
                                    transitionSpec = {
                                        (
                                            fadeIn(tween(400, easing = FastOutSlowInEasing)) +
                                                scaleIn(
                                                    initialScale = 1.04f,
                                                    animationSpec = tween(400, easing = FastOutSlowInEasing),
                                                )
                                            ) togetherWith (
                                            fadeOut(tween(350, easing = FastOutSlowInEasing)) +
                                                scaleOut(
                                                    targetScale = 1.04f,
                                                    animationSpec = tween(350, easing = FastOutSlowInEasing),
                                                )
                                            )
                                    },
                                    label = "heroRotate",
                                ) { index ->
                                    val currentHero = heroCandidates.getOrNull(index)
                                    if (currentHero != null) {
                                        HeroBanner(
                                            item = currentHero,
                                            playing = heroPlaying,
                                            onPlay = { playFirst(currentHero) },
                                            onDetail = { onOpenDetail(currentHero) },
                                            playFocusRequester = heroPlayFocus,
                                        )
                                    }
                                }
                            }
                            // 轮换指示器：整个 Hero 区域正下方居中显示
                            if (heroCandidates.size > 1) {
                                Spacer(Modifier.height(16.dp))
                                HeroIndicators(
                                    count = heroCandidates.size,
                                    current = safeHeroIndex,
                                    onSelect = { i ->
                                        heroIndex = i
                                        heroRotateTick++ // 手动选择后重新计时
                                    },
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                )
                            }
                            // U7：Hero 与下方内容行拉开视觉间距
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }

                // 内容行
                if (groups.latest.isNotEmpty()) {
                    item(key = "row_latest") {
                        Column {
                            Spacer(Modifier.height(24.dp))
                            // L12：最新更新无对应分类页，「更多」按钮原跳 HOME 自身无效 → 去掉
                            ContentRow(
                                title = "最新更新",
                                items = groups.latest,
                                onClickItem = onOpenDetail,
                            )
                        }
                    }
                }
                // 爱奇艺热播（独立数据源，空则整行隐藏）
                if (iqiyiItems.isNotEmpty()) {
                    item(key = "row_iqiyi") {
                        Column {
                            Spacer(Modifier.height(28.dp))
                            ContentRow(
                                title = "爱奇艺热播",
                                items = iqiyiItems,
                                onClickItem = onOpenDetail,
                            )
                        }
                    }
                }
                if (groups.movies.isNotEmpty()) {
                    item(key = "row_movie") {
                        Column {
                            Spacer(Modifier.height(28.dp))
                            ContentRow(
                                title = "热播电影",
                                items = groups.movies,
                                onClickItem = onOpenDetail,
                                showMore = true,
                                onMore = { onOpenCategory(NavPage.MOVIE) },
                            )
                        }
                    }
                }
                if (groups.series.isNotEmpty()) {
                    item(key = "row_series") {
                        Column {
                            Spacer(Modifier.height(28.dp))
                            ContentRow(
                                title = "热门剧集",
                                items = groups.series,
                                onClickItem = onOpenDetail,
                                showMore = true,
                                onMore = { onOpenCategory(NavPage.SERIES) },
                            )
                        }
                    }
                }
                if (groups.variety.isNotEmpty()) {
                    item(key = "row_variety") {
                        Column {
                            Spacer(Modifier.height(28.dp))
                            ContentRow(
                                title = "综艺",
                                items = groups.variety,
                                onClickItem = onOpenDetail,
                                showMore = true,
                                onMore = { onOpenCategory(NavPage.VARIETY) },
                            )
                        }
                    }
                }
                if (groups.anime.isNotEmpty()) {
                    item(key = "row_anime") {
                        Column {
                            Spacer(Modifier.height(28.dp))
                            ContentRow(
                                title = "动漫番剧",
                                items = groups.anime,
                                onClickItem = onOpenDetail,
                                showMore = true,
                                onMore = { onOpenCategory(NavPage.ANIME) },
                            )
                        }
                    }
                }
                item(key = "bottom") { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

/** 首页内容分组（L7：remember 缓存，避免每帧重组重复 filter 500 条）。 */
private data class HomeGroups(
    val latest: List<VideoItem>,
    val movies: List<VideoItem>,
    val series: List<VideoItem>,
    val anime: List<VideoItem>,
    val variety: List<VideoItem>,
)

/**
 * 后台异步补齐 /api/items 剩余批次（每批 500 条）。
 * 与 Web 端 prefetchHomePool 逻辑一致：
 * - 后端按「最新 2000 条 → 全局去重」切片返回
 * - 每批都基于全局总数切片，total 恒为去重后总数，直接拼接无重复
 * - 防兜底：前端按 (vodId, sourceCode) 再去重一次，防并发重复
 * - 内容过滤：根据 filterEnabled 应用分级过滤
 */
private suspend fun prefetchHomeItems(
    startOffset: Int,
    totalCount: Int,
    filterEnabled: Boolean,
    onBatchReceived: (List<VideoItem>) -> Unit
) {
    try {
        var offset = startOffset
        while (offset < totalCount) {
            val resp = ApiClient.get().items(offset = offset, limit = 500)
            if (resp.isSuccessful) {
                val body = resp.body()
                val data = body?.data
                if (body != null && body.code == 0 && data != null && data.items.isNotEmpty()) {
                    // 应用内容分级过滤
                    val filteredItems = if (filterEnabled) {
                        ContentFilter.filterItems(data.items)
                    } else {
                        data.items
                    }
                    onBatchReceived(filteredItems)
                    offset += data.items.size
                } else {
                    break
                }
            } else {
                android.util.Log.e("HomeScreen", "补齐失败: ${resp.errorMessage()}")
                break
            }
        }
        android.util.Log.i("HomeScreen", "后台补齐完成: $offset / $totalCount")
    } catch (e: Exception) {
        android.util.Log.e("HomeScreen", "后台补齐异常", e)
    }
}

/**
 * Hero 横幅（TV 大屏适配版）：全宽背景图 cover 铺满 + 文字层叠在左侧。
 * 与 Web 端设计文档 §5.5 的 55:45 横列布局不同——TV 宽屏上"左文右图"会留大片空白
 * （竖版海报在宽图区里 Crop 后两侧空），改为"全宽背景图 + 左下文字层"是 TV 应用
 * （Netflix/YouTube TV）的标准做法，图自然铺满、比例稳定、文字叠加可读。
 * 焦点默认：立即播放（accent 主按钮，焦点白描边）。
 */
@Composable
private fun HeroBanner(
    item: VideoItem,
    playing: Boolean,
    onPlay: () -> Unit,
    onDetail: () -> Unit,
    playFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(342.dp) // 2026-08-13：Hero 整体缩小 10%（原 380dp）
            .padding(horizontal = 72.dp)
            .clip(shape)
            .background(TvColors.BgSurface),
    ) {
        // 背景海报：cover 铺满整个 Hero（U4：占位/失败态用底色，避免空白块）
        AsyncImage(
            model = resolveMediaUrl(item.pic),
            contentDescription = item.vodName,
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(TvColors.BgElevated),
            error = ColorPainter(TvColors.BgElevated),
            modifier = Modifier.fillMaxSize(),
        )
        // 左侧深色渐变遮罩：从左深到右透明，让左侧文字层在任意海报下都可读
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            TvColors.BgBase.copy(alpha = 0.92f),
                            TvColors.BgBase.copy(alpha = 0.70f),
                            TvColors.BgBase.copy(alpha = 0.15f),
                            Color.Transparent,
                        ),
                        startX = 0f,
                        endX = 1200f,
                    )
                ),
        )
        // 文字层：左中叠加，宽度限制 55% 不进图区右半（Hero 内字号整体比全局小一档）
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.55f)
                .padding(48.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "TUDOUNI TV",
                    style = TvType.Caption.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    color = TvColors.Accent,
                )
                Spacer(Modifier.width(14.dp))
                item.remarks?.let { r ->
                    if (r.isNotBlank()) {
                        Text(
                            text = r,
                            style = TvType.Caption.copy(fontSize = 15.sp),
                            color = TvColors.Score,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = item.vodName ?: "",
                style = TvType.DisplayTitle.copy(fontSize = 36.sp),
                color = TvColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = listOfNotNull(item.typeName, item.year, item.area)
                    .joinToString(" · ")
                    .ifBlank { item.sourceName ?: "" },
                style = TvType.BodyMedium.copy(fontSize = 18.sp),
                color = TvColors.TextSecondary,
            )
            Spacer(Modifier.height(20.dp))
            Row {
                TvButton(
                    text = if (playing) "加载中…" else "立即播放",
                    onClick = onPlay,
                    enabled = !playing,
                    fontSize = 18.sp,
                    modifier = if (playFocusRequester != null) {
                        Modifier.focusRequester(playFocusRequester)
                    } else {
                        Modifier
                    },
                )
                Spacer(Modifier.width(16.dp))
                TvButton(
                    text = "详情",
                    style = TvButtonStyle.Secondary,
                    onClick = onDetail,
                    fontSize = 18.sp,
                )
            }
        }
    }
}

/**
 * Hero 轮换指示器：一排可聚焦横条，左右键切换 Hero。
 * 焦点即选中（无需按 OK）：焦点移入横条立即切换；当前项 accent 金色加宽，其余灰色；焦点白描边。
 */
@Composable
private fun HeroIndicators(
    count: Int,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(count) { i ->
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()
            val selected = i == current
            // 焦点即选中：横条获得焦点时立即触发切换（不消费按键，方向键仍可继续移动焦点）
            LaunchedEffect(isFocused) {
                if (isFocused) onSelect(i)
            }
            Box(
                modifier = Modifier
                    .width(if (selected) 60.dp else 20.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (selected) TvColors.Accent else TvColors.BgElevated)
                    .then(
                        if (isFocused) {
                            Modifier.border(2.dp, Color.White, RoundedCornerShape(3.dp))
                        } else {
                            Modifier
                        }
                    )
                    .focusable(interactionSource = interactionSource),
            )
        }
    }
}

/** 首页 Hero 轮换间隔：每个展示时长。 */
private const val HERO_ROTATE_INTERVAL_MS = 7_000L

/** Hero 轮换切换动画时长（淡入/淡出），与 AnimatedContent transitionSpec 对齐。 */
private const val HERO_TRANSITION_ANIM_MS = 400L

/** 客户端 type_name → 分类（与后端 vodlist.classify_type 保持一致）。 */
private fun classifyType(typeName: String?): String {
    val t = typeName ?: ""
    return when {
        t.contains("动漫") || t.contains("动画") || t.contains("番剧") -> "anime"
        t.contains("综艺") || t.contains("真人秀") || t.contains("选秀") || t.contains("音乐节目") -> "variety"
        Regex("剧(?![情片])").containsMatchIn(t) -> "series"
        else -> "movie"
    }
}
