package com.tudouni.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.tudouni.tv.data.HistoryItem
import com.tudouni.tv.data.SourceSwitcher
import com.tudouni.tv.data.TvRepository
import com.tudouni.tv.data.VideoItem
import com.tudouni.tv.data.errorMessage
import com.tudouni.tv.data.resolveMediaUrl
import com.tudouni.tv.ui.components.EpisodeGrid
import com.tudouni.tv.ui.components.EmptyState
import com.tudouni.tv.ui.components.FullScreenLoading
import com.tudouni.tv.ui.components.TvButton
import com.tudouni.tv.ui.components.TvButtonStyle
import com.tudouni.tv.ui.components.TvChip
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 详情页（对应设计方案 §6.4，全屏 L3 页）：
 * - 左：海报 300×450dp + 主操作（立即播放/继续播放）
 * - 右：片名 44sp/900 → 类型/年份/地区 tags → 简介 → 选集网格（0-9 跳集，当前集 accent 高亮）
 * - 进度记忆：并行拉 /api/history，恢复上次播放集与进度（跨设备断点续播的恢复入口）
 * - M5 修复：同片名其他来源探测 → 「其他来源」chips，点击换源重新拉详情
 * - L4 修复：加载完成后才请求播放按钮焦点（避免 loading 期 requestFocus 落空）
 */
@Composable
fun DetailScreen(
    item: VideoItem,
    onBack: () -> Unit,
    onPlay: (item: VideoItem, url: String, episodes: List<String>, episodeIndex: Int, resumeMs: Long) -> Unit,
) {
    BackHandler(onBack = onBack)

    // 当前条目（换源后更新 sourceCode/sourceName/vodId）
    var currentItem by remember { mutableStateOf(item) }
    var detail by remember { mutableStateOf<DetailResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<HistoryItem?>(null) }
    // M5：其他可用来源（同片名探测结果）
    var altSources by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var switchingSource by remember { mutableStateOf(false) }

    // 当前选中的集（0-based；恢复进度后落在上次播放集）
    var currentIndex by remember { mutableStateOf(0) }
    // 上次进度（秒 → 毫秒；仅对「上次播放集」生效）
    var resumeMs by remember { mutableStateOf(0L) }
    var hasResume by remember { mutableStateOf(false) }

    val playFocusRequester = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 加载详情 + 恢复进度（并行）+ 探测其他源（失败均不影响主体展示）
    suspend fun loadDetailAndHistory(target: VideoItem) {
        coroutineScope {
            val detailDeferred = async {
                try {
                    val resp = ApiClient.get().detail(id = target.vodId ?: "", source = target.sourceCode)
                    if (resp.isSuccessful) resp.body() else null
                } catch (_: Exception) {
                    null
                }
            }
            val historyDeferred = async {
                try {
                    TvRepository.findHistory(target.vodId, target.sourceCode)
                } catch (_: Exception) {
                    null
                }
            }
            val altDeferred = async {
                try {
                    SourceSwitcher.findAlternatives(target.vodName, target.sourceCode)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val d = detailDeferred.await()
            val h = historyDeferred.await()
            val alts = altDeferred.await()
            detail = d
            history = h
            altSources = alts
            val eps = d?.episodes ?: emptyList()
            val histIndex = h?.episodeIndex ?: 0
            val histPosMs = ((h?.position ?: 0.0) * 1000).toLong()
            val histDurMs = ((h?.duration ?: 0.0) * 1000).toLong()
            if (eps.isNotEmpty() && histIndex in 0 until eps.size) {
                currentIndex = histIndex
            }
            if (TvRepository.shouldResume(histPosMs, histDurMs)) {
                resumeMs = histPosMs
                hasResume = true
            }
        }
    }

    LaunchedEffect(item.vodId, item.sourceCode) {
        loading = true
        error = null
        loadDetailAndHistory(currentItem)
        if (detail == null) error = "获取详情失败"
        loading = false
    }

    // L4：加载完成（且详情可用）后才请求播放按钮焦点
    LaunchedEffect(loading, detail) {
        if (!loading && detail != null) {
            playFocusRequester.requestFocus()
        }
    }

    // M5：切换到其他来源
    fun switchToSource(alt: VideoItem) {
        if (switchingSource) return
        scope.launch {
            switchingSource = true
            try {
                currentItem = alt
                detail = null
                error = null
                currentIndex = 0
                resumeMs = 0L
                hasResume = false
                loadDetailAndHistory(alt)
                if (detail == null) error = "该来源暂无内容"
            } finally {
                switchingSource = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            loading -> FullScreenLoading()

            error != null -> EmptyState(
                title = "加载失败",
                description = error,
                actionText = "返回",
                onAction = onBack,
            )

            else -> {
                val info = detail?.videoInfo
                val episodes = detail?.episodes ?: emptyList()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 48.dp, vertical = 28.dp),
                ) {
                    // 顶部：返回按钮（左上，始终可聚焦）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TvButton(
                            text = "← 返回",
                            style = TvButtonStyle.Secondary,
                            onClick = onBack,
                        )
                        Spacer(Modifier.width(20.dp))
                        info?.remarks?.let { r ->
                            if (r.isNotBlank()) {
                                Text(
                                    text = "更新：$r",
                                    style = TvType.BodyMedium,
                                    color = TvColors.Accent,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))

                    Row(verticalAlignment = Alignment.Top) {
                        // 左：海报 + 主操作
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .width(300.dp)
                                    .height(450.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, TvColors.LineStrong, RoundedCornerShape(16.dp)),
                            ) {
                                AsyncImage(
                                    model = resolveMediaUrl(info?.cover ?: currentItem.pic),
                                    contentDescription = info?.title ?: currentItem.vodName,
                                    contentScale = ContentScale.Crop,
                                    placeholder = ColorPainter(TvColors.BgElevated),
                                    error = ColorPainter(TvColors.BgElevated),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Spacer(Modifier.height(28.dp))
                            // 播放主按钮（有进度时显示「继续播放 第N集」）
                            TvButton(
                                text = when {
                                    episodes.isEmpty() -> "暂无可用地址"
                                    hasResume -> "继续播放 第${currentIndex + 1}集"
                                    else -> "立即播放"
                                },
                                onClick = {
                                    val url = episodes.getOrNull(currentIndex)
                                    if (url != null) {
                                        onPlay(
                                            currentItem,
                                            url,
                                            episodes,
                                            currentIndex,
                                            if (hasResume && currentIndex == (history?.episodeIndex ?: 0)) resumeMs else 0L,
                                        )
                                    }
                                },
                                enabled = episodes.isNotEmpty() && !switchingSource,
                                modifier = Modifier
                                    .width(300.dp)
                                    .focusRequester(playFocusRequester),
                            )
                            // M5：其他来源（同片名探测）
                            if (altSources.isNotEmpty()) {
                                Spacer(Modifier.height(18.dp))
                                Text(
                                    text = "其他来源",
                                    style = TvType.Caption.copy(fontSize = 18.sp),
                                    color = TvColors.TextTertiary,
                                )
                                Spacer(Modifier.height(10.dp))
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    altSources.forEach { alt ->
                                        TvButton(
                                            text = if (switchingSource) "切换中…" else "切换到 ${alt.sourceName ?: alt.sourceCode}",
                                            style = TvButtonStyle.Secondary,
                                            enabled = !switchingSource,
                                            onClick = { switchToSource(alt) },
                                            modifier = Modifier.width(300.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(40.dp))

                        // 右：信息 + 选集
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = info?.title ?: currentItem.vodName ?: "",
                                style = TvType.DetailTitle,
                                color = TvColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(14.dp))
                            Row {
                                listOfNotNull(
                                    info?.type,
                                    info?.year,
                                    info?.area,
                                    info?.sourceName,
                                ).filter { it.isNotBlank() }.forEach { tag ->
                                    // U12：TV 远距离观看，tag 字号从 20sp 提到 22sp
                                    Text(
                                        text = tag,
                                        style = TvType.Caption.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium),
                                        color = TvColors.TextSecondary,
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .background(TvColors.BgElevated, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            val desc = info?.desc
                            Text(
                                text = when {
                                    desc.isNullOrEmpty() -> "暂无简介"
                                    desc.length > 240 -> desc.take(240) + "…"
                                    else -> desc
                                },
                                style = TvType.BodyMedium,
                                color = TvColors.TextSecondary,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(32.dp))
                            Text(
                                text = "选集（${episodes.size}）",
                                style = TvType.RowTitle.copy(fontSize = 24.sp),
                                color = TvColors.TextPrimary,
                            )
                            if (episodes.isEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = if (altSources.isNotEmpty()) "当前来源暂无播放地址，可用左侧「其他来源」切换" else "暂无可用播放地址",
                                    style = TvType.BodyMedium,
                                    color = TvColors.TextTertiary,
                                )
                            } else {
                                Spacer(Modifier.height(16.dp))
                                EpisodeGrid(
                                    count = episodes.size,
                                    currentIndex = currentIndex,
                                    onSelect = { index ->
                                        currentIndex = index
                                        val url = episodes.getOrNull(index)
                                        if (url != null) {
                                            onPlay(
                                                currentItem,
                                                url,
                                                episodes,
                                                index,
                                                if (hasResume && index == (history?.episodeIndex ?: 0)) resumeMs else 0L,
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .height(340.dp)
                                        .fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
