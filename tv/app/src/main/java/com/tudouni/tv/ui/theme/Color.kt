package com.tudouni.tv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 设计令牌 —— 与 Web 端 css/design.css :root 逐条对应（见 TV 端设计方案 §7.3 / §10）。
 * TV 端只做深色主题，令牌表保留为未来「跟随系统」扩展点。
 */
object TvColors {
    // 底色阶
    val BgBase = Color(0xFF0E1116)            // --bg-base      页面底色（深蓝黑）
    val BgSurface = Color(0xFF151B24)         // --bg-surface    面板/卡片面
    val BgElevated = Color(0xFF1D2430)        // --bg-elevated   次级控件面（输入框/按钮底）
    val BgHover = Color(0xFF273043)           // --bg-hover      悬停/次级焦点底

    // 文本
    val TextPrimary = Color(0xFFF2F5F8)       // --text-1        主文本
    val TextSecondary = Color(0xFFB6C0CC)     // --text-2        次级文本
    val TextTertiary = Color(0xFF7D8896)      // --text-3        弱化文本/提示

    // 品牌色
    val Accent = Color(0xFFFFB020)            // --accent        琥珀金：主按钮/焦点/选中
    val AccentStrong = Color(0xFFFFC95C)      // --accent-strong 高亮
    val AccentInk = Color(0xFF3A2A00)         // --accent-ink    琥珀底上的深色文字

    // 语义色
    val Score = Color(0xFFFFD35C)             // --score         评分黄
    val Danger = Color(0xFFFF5F6D)            // --like/--danger 点赞/危险
    val Success = Color(0xFF34D399)           // --success       成功
    val Info = Color(0xFF60A5FA)              // --info          信息

    // 线条与遮罩
    val Line = Color(0x14FFFFFF)              // --line          rgba(255,255,255,.08)
    val LineStrong = Color(0x29FFFFFF)        // --line-strong   rgba(255,255,255,.16)
    val Glass = Color(0xB8161C26)             // --glass         rgba(22,28,38,.72)
    val Scrim = Color(0xD106080C)             // 弹窗遮罩        rgba(6,8,12,.82)
    val FocusShadow = Color(0x80000000)       // 焦点阴影        0 8dp 24dp rgba(0,0,0,.5)

    // 播放按钮浮层 / 渐变
    val PlayOverlay = Color(0x66000000)       // 焦点态播放浮层底
    val PosterScrim = Color(0xB3000000)       // 海报底部渐变
}
