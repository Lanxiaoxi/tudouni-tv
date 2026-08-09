package com.tudouni.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tudouni.tv.data.HistoryItem
import com.tudouni.tv.data.TvRepository
import com.tudouni.tv.data.VideoItem
import com.tudouni.tv.data.resolveMediaUrl
import com.tudouni.tv.ui.components.EmptyState
import com.tudouni.tv.ui.components.FullScreenLoading
import com.tudouni.tv.ui.components.PageHorizontalPadding
import com.tudouni.tv.ui.components.TvButton
import com.tudouni.tv.ui.components.TvButtonStyle
import com.tudouni.tv.ui.components.TvDialog
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 历史记录页（对应设计方案 §6.6）：服务端观看历史（进度记忆的展示端）。
 * - 分组：今天 / 昨天 / 更早；每条：小海报 + 片名 + 进度条 + 百分比 + 继续观看
 * - 继续观看 → 跨设备断点续播（服务端存的 episodes/episode_index/position）
 * - 单条删除 / 清空历史（确认弹窗）
 */
@Composable
fun HistoryScreen(
    onOpenDetail: (VideoItem) -> Unit,
    onPlay: (VideoItem, String, List<String>, Int, Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var items by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    var deleteTarget by remember { mutableStateOf<HistoryItem?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        error = null
        try {
            items = TvRepository.fetchHistory(100)
        } catch (e: Exception) {
            error = "网络错误: ${e.message}"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(retryKey) { load() }

    Column(Modifier.fillMaxSize()) {
        // 页标题 + 总数 + 清空
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
            Text(text = "历史记录", style = TvType.PageTitle, color = TvColors.TextPrimary)
            Spacer(Modifier.width(20.dp))
            if (!loading && items.isNotEmpty()) {
                Text(
                    text = "共 ${items.size} 条",
                    style = TvType.BodyMedium,
                    color = TvColors.TextTertiary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (items.isNotEmpty()) {
                TvButton(
                    text = "清空历史",
                    style = TvButtonStyle.Secondary,
                    onClick = { showClearConfirm = true },
                )
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

            items.isEmpty() -> EmptyState(
                title = "暂无观看记录",
                description = "看过的内容会出现在这里，换设备登录同一账号进度也能同步",
            )

            else -> {
                val groups = groupByDay(items)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp),
                ) {
                    groups.forEach { (label, groupItems) ->
                        item(key = "group_$label") {
                            Text(
                                text = label,
                                style = TvType.RowTitle.copy(fontSize = 26.sp),
                                color = TvColors.TextSecondary,
                                modifier = Modifier
                                    .padding(horizontal = PageHorizontalPadding)
                                    .padding(top = 24.dp, bottom = 14.dp),
                            )
                        }
                        items(groupItems, key = { it.id ?: it.hashCode().toString() }) { h ->
                            HistoryRow(
                                item = h,
                                onOpen = { onOpenDetail(h.toVideoItem()) },
                                onContinue = {
                                    val eps = h.episodes ?: emptyList()
                                    val index = h.episodeIndex ?: 0
                                    val url = eps.getOrNull(index)
                                    if (url != null) {
                                        val posMs = ((h.position ?: 0.0) * 1000).toLong()
                                        val durMs = ((h.duration ?: 0.0) * 1000).toLong()
                                        val resume = if (TvRepository.shouldResume(posMs, durMs)) posMs else 0L
                                        onPlay(h.toVideoItem(), url, eps, index, resume)
                                    } else {
                                        onOpenDetail(h.toVideoItem())
                                    }
                                },
                                onDelete = { deleteTarget = h },
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除单条
    deleteTarget?.let { target ->
        TvDialog(
            title = "删除这条记录？",
            message = "「${target.title}」的观看进度将被移除",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    TvRepository.deleteHistoryItem(target)
                    load()
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }

    // 清空全部
    if (showClearConfirm) {
        TvDialog(
            title = "清空全部历史？",
            message = "所有观看进度将被移除，此操作不可恢复",
            confirmText = "清空",
            danger = true,
            onConfirm = {
                showClearConfirm = false
                scope.launch {
                    TvRepository.clearHistory()
                    load()
                }
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

/** 按 今天/昨天/更早 分组。 */
private fun groupByDay(items: List<HistoryItem>): List<Pair<String, List<HistoryItem>>> {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val yesterdayStart = (today.timeInMillis / 1000) - 86400
    val todayStart = today.timeInMillis / 1000
    val todayList = items.filter { (it.timestamp ?: 0) >= todayStart }
    val yesterdayList = items.filter { (it.timestamp ?: 0) in yesterdayStart until todayStart }
    val earlierList = items.filter { (it.timestamp ?: 0) < yesterdayStart }
    return buildList {
        if (todayList.isNotEmpty()) add("今天" to todayList)
        if (yesterdayList.isNotEmpty()) add("昨天" to yesterdayList)
        if (earlierList.isNotEmpty()) add("更早" to earlierList)
    }
}

/** 秒 → m:ss / h:mm:ss。 */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

/** 历史行（对应 §6.6）：小海报 + 信息 + 进度条 + 继续观看 + 删除。 */
@Composable
private fun HistoryRow(
    item: HistoryItem,
    onOpen: () -> Unit,
    onContinue: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)

    val positionMs = ((item.position ?: 0.0) * 1000).toLong()
    val durationMs = ((item.duration ?: 0.0) * 1000).toLong()
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageHorizontalPadding)
            .padding(vertical = 10.dp)
            .graphicsLayer {
                scaleX = if (isFocused) 1.03f else 1f
                scaleY = if (isFocused) 1.03f else 1f
            }
            .then(
                if (isFocused) {
                    Modifier.border(2.dp, TvColors.Accent, shape)
                } else {
                    Modifier.border(1.dp, TvColors.Line, shape)
                }
            )
            .clip(shape)
            .background(TvColors.BgSurface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onOpen,
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 小海报 96dp
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(144.dp)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            AsyncImage(
                model = resolveMediaUrl(item.pic),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(24.dp))

        // 信息 + 进度条
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title ?: "",
                style = TvType.PosterTitle,
                color = if (isFocused) TvColors.Accent else TvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "第 ${(item.episodeIndex ?: 0) + 1} 集 · ${formatTime(positionMs)} / ${formatTime(durationMs)}",
                style = TvType.Caption,
                color = TvColors.TextTertiary,
            )
            Spacer(Modifier.height(14.dp))
            // 进度条（6dp 高，accent 渐变填充）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(TvColors.BgElevated),
            ) {
                if (progress > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(6.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(TvColors.Accent, TvColors.AccentStrong),
                                )
                            ),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "已观看 ${(progress * 100).toInt()}%",
                style = TvType.Caption,
                color = TvColors.TextSecondary,
            )
        }

        Spacer(Modifier.width(24.dp))

        // 操作：继续观看 / 删除
        Column(horizontalAlignment = Alignment.End) {
            TvButton(
                text = "继续观看",
                onClick = onContinue,
                modifier = Modifier.width(180.dp),
            )
            Spacer(Modifier.height(12.dp))
            TvButton(
                text = "删除",
                style = TvButtonStyle.Ghost,
                onClick = onDelete,
                modifier = Modifier.width(180.dp),
            )
        }
    }
}
