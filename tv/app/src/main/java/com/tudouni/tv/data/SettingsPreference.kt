package com.tudouni.tv.data

import android.content.Context

/**
 * 用户设置偏好存储（SharedPreferences 封装）。
 * 用于保存分级过滤开关状态、其他用户偏好设置。
 */
class SettingsPreference(context: Context) {
    
    private val prefs = context.getSharedPreferences("tudouni_settings", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_CONTENT_FILTER_ENABLED = "content_filter_enabled"
        private const val KEY_AUTOPLAY_ENABLED = "autoplay_enabled"
    }
    
    /**
     * 获取内容分级过滤状态。
     * 默认为 true（启用过滤）。
     */
    fun isContentFilterEnabled(): Boolean {
        return prefs.getBoolean(KEY_CONTENT_FILTER_ENABLED, true)
    }
    
    /**
     * 设置内容分级过滤状态。
     */
    fun setContentFilterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTENT_FILTER_ENABLED, enabled).apply()
    }
    
    /**
     * 获取自动连播状态。
     * 默认为 true（启用自动连播）。
     */
    fun isAutoplayEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTOPLAY_ENABLED, true)
    }
    
    /**
     * 设置自动连播状态。
     */
    fun setAutoplayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOPLAY_ENABLED, enabled).apply()
    }
}
