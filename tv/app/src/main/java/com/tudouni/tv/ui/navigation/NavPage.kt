package com.tudouni.tv.ui.navigation

/**
 * L1 顶层导航（左侧导航栏），对应设计方案 §3.1。
 * 电影/剧集/动漫/综艺 进入分类页并预选对应分类；历史/搜索/设置进各自页面。
 */
enum class NavPage(val label: String, val icon: String) {
    HOME("首页", "⌂"),
    MOVIE("电影", "▶"),
    SERIES("剧集", "▤"),
    ANIME("动漫", "✦"),
    VARIETY("综艺", "◉"),
    HISTORY("历史观看", "↺"),
    SEARCH("搜索", "⌕"),
    SETTINGS("设置", "⚙"),
    ;

    companion object {
        /** 默认页（首页）。 */
        @JvmField
        val DEFAULT: NavPage = HOME
    }
}
