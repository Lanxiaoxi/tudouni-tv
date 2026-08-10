package com.tudouni.tv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 圆角体系 —— 对应设计方案 §1.3（Web px → TV dp 换算）。 */
object TvShapes {
    /** 海报卡/内容卡圆角 16dp（Web 14px） */
    val Card = RoundedCornerShape(16.dp)

    /** 按钮圆角 14dp（Web 12-13px） */
    val Button = RoundedCornerShape(14.dp)

    /** 弹窗圆角 24dp（Web 20px） */
    val Dialog = RoundedCornerShape(24.dp)

    /** Chip 全圆角 */
    val Pill = RoundedCornerShape(24.dp)

    /** 选集单元圆角 12dp */
    val Episode = RoundedCornerShape(12.dp)

    /** 导航项圆角 */
    val NavItem = RoundedCornerShape(12.dp)
}
