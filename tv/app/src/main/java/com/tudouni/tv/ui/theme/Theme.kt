package com.tudouni.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * TV 深色主题（固定深色，不做浅色）—— 品牌色琥珀金 #FFB020。
 * 令牌唯一来源：TvColors / TvType / TvShapes。
 */
private val Scheme = darkColorScheme(
    primary = TvColors.Accent,
    onPrimary = TvColors.AccentInk,
    primaryContainer = TvColors.BgElevated,
    onPrimaryContainer = TvColors.AccentStrong,
    secondary = TvColors.Info,
    onSecondary = TvColors.BgBase,
    background = TvColors.BgBase,
    onBackground = TvColors.TextPrimary,
    surface = TvColors.BgSurface,
    onSurface = TvColors.TextPrimary,
    surfaceVariant = TvColors.BgElevated,
    onSurfaceVariant = TvColors.TextSecondary,
    outline = TvColors.Line,
    outlineVariant = TvColors.LineStrong,
    error = TvColors.Danger,
    onError = TvColors.BgBase,
    tertiary = TvColors.Score,
    onTertiary = TvColors.BgBase,
)

@Composable
fun TudouniTVTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = TvTypography,
        content = content,
    )
}
