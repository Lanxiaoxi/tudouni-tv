package com.tudouni.tv.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号阶梯（sp）—— 对标主流 TV 应用（爱奇艺/腾讯视频 TV）的紧凑观感。
 * 比早期「10 英尺理论」版更收敛：海报更大密度、标题 36-48、正文 22-26。
 */
object TvType {
    /** Hero 大标题 48sp / 900（Sora） */
    val DisplayTitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 48.sp,
        letterSpacing = 0.5.sp,
    )

    /** 页面标题 36sp / 900 */
    val PageTitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 36.sp,
    )

    /** 内容行标题 30sp / 800 */
    val RowTitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp,
    )

    /** 详情片名 40sp / 900 */
    val DetailTitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 40.sp,
    )

    /** 正文大 26sp */
    val BodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
    )

    /** 正文中 24sp */
    val BodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
    )

    /** 按钮文字 24sp / 700 */
    val ButtonLabel = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
    )

    /** 海报卡下方片名 22sp / 500 */
    val PosterTitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
    )

    /** 弱化信息 20sp（仅非关键标注用） */
    val Caption = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
    )
}

/** MaterialTheme 基础 Typography（框架控件兜底），业务文本优先用 TvType。 */
val TvTypography = Typography(
    displayLarge = TvType.DisplayTitle,
    headlineLarge = TvType.PageTitle,
    headlineMedium = TvType.DetailTitle,
    titleLarge = TvType.RowTitle,
    titleMedium = TvType.BodyLarge,
    bodyLarge = TvType.BodyLarge,
    bodyMedium = TvType.BodyMedium,
    bodySmall = TvType.Caption,
    labelLarge = TvType.ButtonLabel,
)
