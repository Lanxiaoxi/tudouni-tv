package com.tudouni.tv.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tudouni.tv.data.ApiClient
import com.tudouni.tv.data.DetailResponse
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
import kotlinx.coroutines.launch

/**
 * 首页（对应设计方案 §6.1）：Hero 横幅 + 多内容行（按 type_name 客户端分组）。
 * 数据源 /api/items（镜像表毫秒级），一次拉 500 条后本地分组。
 */
@Composable
fun HomeScreen(
    username: String,
    onOpenDetail: (VideoItem) -> Unit,
    onPlay: (VideoItem, String, List<String>, Int, Long) -> Unit,
    onOpenCategory: (NavPage) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var items by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    // Hero「立即播放」需要先拉详情拿第一集地址
    var heroPlaying by remember { mutableStateOf(false) }

    // 初始焦点：Compose 不会自动聚焦第一个控件，必须显式请求，
    // 否则整页方向键焦点导航不工作。加载完成后把焦点给 Hero「立即播放」。
    val heroPlayFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(loading, items.isEmpty()) {
        if (!loading && items.isNotEmpty()) {
            heroPlayFocus.requestFocus()
        }
    }

    LaunchedEffect(retryKey) {
        loading = true
        error = null
        try {
            val resp = ApiClient.get().items(offset = 0, limit = 500)
            if (resp.isSuccessful) {
                val body = resp.body()
                val data = body?.data
                if (body != null && body.code == 0 && data != null) {
                    items = data.items
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

    // 客户端分组（与后端 classify_type 同逻辑）；L7：remember 缓存，避免每帧重组重算
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
        loading && items.isEmpty() -> FullScreenLoading()

        error != null && items.isEmpty() -> EmptyState(
            title = "加载失败",
            description = error,
            actionText = "重试",
            onAction = { retryKey++ },
        )

        else -> {
            val hero = items.firstOrNull()
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

                // Hero 横幅
                if (hero != null) {
                    item(key = "hero") {
                        Column {
                            HeroBanner(
                                item = hero,
                                playing = heroPlaying,
                                onPlay = { playFirst(hero) },
                                onDetail = { onOpenDetail(hero) },
                                playFocusRequester = heroPlayFocus,
                            )
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
                item(key = "bottom") { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

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

/** 首页内容分组（L7：remember 缓存，避免每帧重组重复 filter 500 条）。 */
private data class HomeGroups(
    val latest: List<VideoItem>,
    val movies: List<VideoItem>,
    val series: List<VideoItem>,
    val anime: List<VideoItem>,
    val variety: List<VideoItem>,
)

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
            .height(380.dp)
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
