package com.tudouni.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.tudouni.tv.data.TvRepository
import com.tudouni.tv.data.VideoItem
import com.tudouni.tv.player.PlayerController
import com.tudouni.tv.ui.components.EpisodeGrid
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放页（对应设计方案 §6.5）：
 * - 左 65%：Media3 播放器（16:9，PlayerView 自带控制条）
 * - 右 35%：片名 + 选集网格（当前集 accent 高亮，0-9 跳集，焦点移动即切集）
 * - 进度记忆（跨设备断点续播）：
 *   ① 恢复：进入时 seekTo(resumePositionMs)（详情页已判定是否值得恢复）
 *   ② 上报：播放中每 10s + 退出页面时一次 → PUT /api/history（服务端按 user_id 隔离，天然跨设备）
 */
@Composable
fun PlayerScreen(
    item: VideoItem,
    url: String,
    episodes: List<String>,
    episodeIndex: Int,
    resumePositionMs: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val controller = remember { PlayerController(context) }
    val scope = rememberCoroutineScope()

    var currentIndex by remember { mutableIntStateOf(episodeIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0))) }
    // 展示用：当前是否已恢复进度（提示条）
    var showResumeTip by remember { mutableStateOf(resumePositionMs > 0) }

    // 初始播放（带恢复位置）
    LaunchedEffect(Unit) {
        controller.play(url, resumePositionMs)
    }

    // 播放中每 10s 上报进度（rememberUpdatedState 保证读到最新集数；fire-and-forget，失败静默）
    val latestIndex by rememberUpdatedState(currentIndex)
    LaunchedEffect(controller) {
        while (true) {
            delay(TvRepository.PROGRESS_REPORT_INTERVAL_MS)
            val pos = controller.currentPositionMs()
            val dur = controller.durationMs()
            TvRepository.reportProgress(
                item = item,
                episodes = episodes,
                episodeIndex = latestIndex,
                positionMs = pos,
                durationMs = dur,
            )
        }
    }

    // 退出页面：立即上报一次（尽力而为的协程；每 10s 兜底已覆盖大部分数据）
    DisposableEffect(controller) {
        onDispose {
            val pos = controller.currentPositionMs()
            val dur = controller.durationMs()
            if (pos > 0 && dur > 0) {
                CoroutineScope(Dispatchers.IO).launch {
                    TvRepository.reportProgress(item, episodes, currentIndex, pos, dur)
                }
            }
            controller.release()
        }
    }

    BackHandler(onBack = onBack)

    fun switchEpisode(index: Int) {
        if (index !in episodes.indices) return
        currentIndex = index
        showResumeTip = false
        controller.playEpisode(episodes[index])
        // 立即上报新集（标记换集时刻，供下次恢复）
        scope.launch {
            TvRepository.reportProgress(item, episodes, index, 0L, 0L)
        }
    }

    Row(Modifier.fillMaxSize().background(TvColors.BgBase)) {
        // 左：播放器
        Column(
            modifier = Modifier
                .weight(0.65f)
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = controller.player
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = item.vodName ?: "",
                style = TvType.RowTitle.copy(fontSize = 22.sp),
                color = TvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showResumeTip) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "已从上次进度继续播放（第 ${currentIndex + 1} 集）",
                    style = TvType.Caption,
                    color = TvColors.Success,
                )
            }
        }

        // 右：选集
        Column(
            modifier = Modifier
                .weight(0.35f)
                .fillMaxSize()
                .background(TvColors.BgSurface)
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Text(
                text = "选集（${episodes.size}）",
                style = TvType.RowTitle.copy(fontSize = 24.sp),
                color = TvColors.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "方向键选择 · 数字键跳集",
                style = TvType.Caption,
                color = TvColors.TextTertiary,
            )
            Spacer(Modifier.height(20.dp))
            EpisodeGrid(
                count = episodes.size,
                currentIndex = currentIndex,
                onSelect = { index -> switchEpisode(index) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
