package com.tudouni.tv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/**
 * Media3 ExoPlayer 封装（对应设计方案 §7.5）：
 * - HLS（m3u8）原生播放，直连资源站不经服务器（与 Web 端架构一致）
 * - 提供进度读取/seek/换集等播放页所需的最小接口
 *
 * 进度上报与恢复策略见 PlayerScreen（上报：每 10s + 退出；恢复：详情页带入 resumePositionMs）。
 */
class PlayerController(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    /** 播放指定地址，可选起始位置（毫秒）。 */
    fun play(url: String, startPositionMs: Long = 0L) {
        player.setMediaItem(MediaItem.fromUri(url), startPositionMs)
        player.prepare()
        player.playWhenReady = true
    }

    /** 播放中换集（从 0 开始）。 */
    fun playEpisode(url: String) = play(url, 0L)

    fun seekTo(positionMs: Long) {
        if (positionMs > 0) player.seekTo(positionMs)
    }

    /** 当前播放位置（毫秒；未就绪返回 0）。 */
    fun currentPositionMs(): Long = player.currentPosition.takeIf { it > 0 } ?: 0L

    /** 总时长（毫秒；未知返回 0）。 */
    fun durationMs(): Long = player.duration.takeIf { it > 0 } ?: 0L

    fun isPlaying(): Boolean = player.isPlaying

    fun release() {
        player.release()
    }
}
