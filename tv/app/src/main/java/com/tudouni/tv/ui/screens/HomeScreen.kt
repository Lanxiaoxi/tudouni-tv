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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

    // 客户端分组（与后端 classify_type 同逻辑）
    val latest = items.take(12)
    val movies = items.filter { classifyType(it.typeName) == "movie" }.take(8)
    val series = items.filter { classifyType(it.typeName) == "series" }.take(8)
    val anime = items.filter { classifyType(it.typeName) == "anime" }.take(8)
    val variety = items.filter { classifyType(it.typeName) == "variety" }.take(8)

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
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }

                // 内容行
                if (latest.isNotEmpty()) {
                    item(key = "row_latest") {
                        Column {
                            Spacer(Modifier.height(32.dp))
                            ContentRow(
                                title = "最新更新",
                                items = latest,
                                onClickItem = onOpenDetail,
                                showMore = true,
                                onMore = { onOpenCategory(NavPage.HOME) },
                            )
                        }
                    }
                }
                if (movies.isNotEmpty()) {
                    item(key = "row_movie") {
                        Column {
                            Spacer(Modifier.height(40.dp))
                            ContentRow(
                                title = "热播电影",
                                items = movies,
                                onClickItem = onOpenDetail,
                                showMore = true,
                                onMore = { onOpenCategory(NavPage.MOVIE) },
                            )
                        }
                    }
                }
                if (series.isNotEmpty()) {
                    item(key = "row_series") {
                        Column {
                            Spacer(Modifier.height(40.dp))
                            ContentRow(
                                title = "热门剧集",
                                items = series,
                                onClickItem = onOpenDetail,
                                showMore = true,
                                onMore = { onOpenCategory(NavPage.SERIES) },
                            )
                        }
                    }
                }
                if (anime.isNotEmpty()) {
                    item(key = "row_anime") {
                        Column {
                            Spacer(Modifier.height(40.dp))
                            ContentRow(
                                title = "动漫番剧",
                                items = anime,
                                onClickItem = onOpenDetail,
                                showMore = true,
                                onMore = { onOpenCategory(NavPage.ANIME) },
                            )
                        }
                    }
                }
                if (variety.isNotEmpty()) {
                    item(key = "row_variety") {
                        Column {
                            Spacer(Modifier.height(40.dp))
                            ContentRow(
                                title = "综艺",
                                items = variety,
                                onClickItem = onOpenDetail,
                                showMore = true,
                                onMore = { onOpenCategory(NavPage.VARIETY) },
                            )
                        }
                    }
                }
                item(key = "bottom") { Spacer(Modifier.height(40.dp)) }
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

/**
 * Hero 横幅（对应设计方案 §5.5）：全宽 16:9 区，左文字（badge+标题+评分+简介+主按钮）右海报图。
 * 默认焦点：立即播放（accent 主按钮，焦点白描边）。
 */
@Composable
private fun HeroBanner(
    item: VideoItem,
    playing: Boolean,
    onPlay: () -> Unit,
    onDetail: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(460.dp)
            .padding(horizontal = 72.dp)
            .clip(shape)
            .background(TvColors.BgSurface),
    ) {
        // 左：文字区 55%
        Column(
            modifier = Modifier
                .weight(0.55f)
                .padding(horizontal = 44.dp, vertical = 40.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "TUDOUNI TV",
                    style = TvType.Caption.copy(fontWeight = FontWeight.Bold),
                    color = TvColors.Accent,
                )
                Spacer(Modifier.width(16.dp))
                item.remarks?.let { r ->
                    if (r.isNotBlank()) {
                        Text(
                            text = r,
                            style = TvType.Caption,
                            color = TvColors.Score,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = item.vodName ?: "",
                style = TvType.DisplayTitle.copy(fontSize = 48.sp),
                color = TvColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = listOfNotNull(item.typeName, item.year, item.area)
                    .joinToString(" · ")
                    .ifBlank { item.sourceName ?: "" },
                style = TvType.BodyMedium,
                color = TvColors.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            Row {
                TvButton(
                    text = if (playing) "加载中…" else "立即播放",
                    onClick = onPlay,
                    enabled = !playing,
                )
                Spacer(Modifier.width(20.dp))
                TvButton(
                    text = "详情",
                    style = TvButtonStyle.Secondary,
                    onClick = onDetail,
                )
            }
        }
        // 右：海报图 45%
        Box(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight(),
        ) {
            AsyncImage(
                model = resolveMediaUrl(item.pic),
                contentDescription = item.vodName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 左侧淡出到纯色，与文字区衔接
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(TvColors.BgSurface, Color.Transparent),
                            startX = 0f,
                            endX = 400f,
                        )
                    ),
            )
        }
    }
}
