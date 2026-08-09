package com.tudouni.tv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 播放错误信息（H2 修复：黑屏静默 → 错误反馈）。 */
data class PlayerError(val message: String, val cause: Throwable? = null)

/**
 * Media3 ExoPlayer 封装（对应设计方案 §7.5）：
 * - HLS（m3u8）原生播放，直连资源站不经服务器（与 Web 端架构一致）
 * - 提供进度读取/seek/换集等播放页所需的最小接口
 * - H2 修复：监听 Player.Listener，暴露 [error]（播放失败）与 [isBuffering]（缓冲中）
 *   供 PlayerScreen 显示错误浮层与加载指示
 *
 * 进度上报与恢复策略见 PlayerScreen（上报：每 10s + 退出；恢复：详情页带入 resumePositionMs）。
 */
class PlayerController(context: Context) {

    private val _error = MutableStateFlow<PlayerError?>(null)
    /** 最近一次播放错误（播放成功后被清除），UI 观察此状态显示错误浮层。 */
    val error: StateFlow<PlayerError?> = _error.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    /** 是否处于缓冲中（换集/网络加载），UI 观察此状态显示加载指示。 */
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    /** 当前是否正在播放（供 UI 显示播放/暂停态）。 */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val msg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败，请检查网络后重试"
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "播放地址失效（404），请换源或稍后重试"
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "视频编码不支持（可能为 HEVC），请换源"
                    else -> "播放失败（${error.errorCode}）"
                }
                _error.value = PlayerError(msg, error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = playbackState == Player.STATE_BUFFERING
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
        })
    }

    /** 播放指定地址，可选起始位置（毫秒）。 */
    fun play(url: String, startPositionMs: Long = 0L) {
        _error.value = null
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

    fun release() {
        player.release()
    }
}
