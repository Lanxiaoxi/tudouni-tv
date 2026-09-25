package com.tudouni.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.ui.navigation.NavPage
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 左侧导航栏（对应设计方案 §5.4 变体——上下分区布局）：
 * - 宽 200dp，深色面；品牌下方为「上区」：搜索 / 历史观看 / 设置；
 *   中间渐变分隔线之下为「下区」：首页 / 电影 / 剧集 / 动漫 / 综艺
 * - 焦点：整行 --bg-hover 圆角 + 文本高亮；选中页：琥珀左竖条 + 文本 800
 * - 行为：↑↓ 循环移动（整体贯穿两区：搜索 ↑ → 综艺，综艺 ↓ → 搜索）；
 *   OK 切换页面。循环通过 onPreviewKeyEvent 在系统焦点移动前拦截实现。
 */
@Composable
fun TvNavRail(
    currentPage: NavPage,
    onSelect: (NavPage) -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: Boolean = false,
    onFocusConsumed: () -> Unit = {},
) {
    // 显示顺序：上区（搜索/历史观看/设置）+ 下区（首页/电影/剧集/动漫/综艺）
    val pages = remember {
        listOf(
            NavPage.SEARCH, NavPage.HISTORY, NavPage.SETTINGS,
            NavPage.HOME, NavPage.MOVIE, NavPage.SERIES, NavPage.ANIME, NavPage.VARIETY,
        )
    }
    val focusRequesters = remember { pages.map { FocusRequester() } }
    val brandBrush = remember {
        Brush.horizontalGradient(listOf(TvColors.AccentStrong, TvColors.Accent))
    }

    // 初始焦点：进入主框架时落在当前页导航项（默认首页）。
    // 与旧实现的两点区别：
    // ① 焦点请求会重试，直到导航项**真的**拿到焦点（由 NavItem 的 onFocused 回调确认）；
    //    旧实现无条件调用 onFocusConsumed，一旦首次请求失败就再没有重试机会，
    //    首屏会没有任何元素处于焦点态（遥控器第一下按下去毫无反馈）。
    // ② 只确认真的拿到焦点才消费 initialFocus，避免从详情/播放页返回时焦点被抢回导航栏。
    var initialFocusDone by remember { mutableStateOf(!initialFocus) }
    LaunchedEffect(Unit) {
        if (!initialFocus) return@LaunchedEffect
        val idx = pages.indexOf(currentPage).coerceAtLeast(0)
        repeat(FOCUS_RETRY_ATTEMPTS) {
            if (initialFocusDone) return@LaunchedEffect
            runCatching { focusRequesters[idx].requestFocus() }
            delay(FOCUS_RETRY_MS)
        }
    }

    Column(
        modifier = modifier
            .width(200.dp)
            .fillMaxHeight()
            .background(TvColors.BgSurface.copy(alpha = 0.92f))
            .padding(vertical = 28.dp),
    ) {
        // 品牌（琥珀渐变）
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(22.dp)
                    .background(brandBrush),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Tudouni TV",
                style = TvType.RowTitle.copy(
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    brush = brandBrush,
                ),
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(28.dp))

        // 上区：搜索 / 历史观看 / 设置
        for (i in 0 until 3) {
            NavItem(
                page = pages[i],
                selected = pages[i] == currentPage,
                focusRequester = focusRequesters[i],
                isFirst = i == 0,
                isLast = false,
                wrapTo = if (i == 0) focusRequesters.last() else null,
                onClick = { onSelect(pages[i]) },
                onFocused = {
                    initialFocusDone = true
                    onFocusConsumed()
                },
            )
        }

        // 分区渐变分隔线（琥珀，左亮右淡）
        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(TvColors.Accent.copy(alpha = 0.35f), TvColors.Accent.copy(alpha = 0.05f)),
                    )
                ),
        )

        // 下区：首页 / 电影 / 剧集 / 动漫 / 综艺
        for (i in 3 until pages.size) {
            NavItem(
                page = pages[i],
                selected = pages[i] == currentPage,
                focusRequester = focusRequesters[i],
                isFirst = false,
                isLast = i == pages.lastIndex,
                wrapTo = if (i == pages.lastIndex) focusRequesters.first() else null,
                onClick = { onSelect(pages[i]) },
                onFocused = {
                    initialFocusDone = true
                    onFocusConsumed()
                },
            )
        }
    }
}

@Composable
private fun NavItem(
    page: NavPage,
    selected: Boolean,
    focusRequester: FocusRequester,
    isFirst: Boolean,
    isLast: Boolean,
    wrapTo: FocusRequester?,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // 焦点真的落到本项时通知外层（用于确认「初始焦点请求已成功」，失败则外层继续重试）
    LaunchedEffect(isFocused) {
        if (isFocused) onFocused()
    }
    val scope = rememberCoroutineScope()
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(
            durationMillis = if (isFocused) 250 else 150,
            easing = FastOutSlowInEasing,
        ),
        label = "navScale",
    )

    val bg = when {
        isFocused -> TvColors.BgHover
        selected -> TvColors.BgElevated
        else -> Color.Transparent
    }
    val textColor = when {
        selected -> TvColors.TextPrimary
        isFocused -> TvColors.TextPrimary
        else -> TvColors.TextSecondary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                }
                .fillMaxSize()
                .background(bg, TvShapes.NavItem)
                // focusRequester 必须在 clickable 之前：Modifier.a().b() 里 a 是 b 的父节点，
                // 而 FocusRequester.requestFocus() 只**向下**遍历寻找 FocusTarget
                // （Compose 1.7.5 FocusRequester.kt：node.visitChildren(Nodes.FocusTarget)）。
                // 写在 clickable 之后就找不到焦点目标——requestFocus 静默返回 false 且不抛异常，
                // 表现为「初始焦点不生效」+「↑↓ 环绕键被消费但焦点不动」。
                .focusRequester(focusRequester)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                // 环绕：仅首/末项拦截边界方向键；用协程延迟一帧请求焦点，
                // 避免与系统焦点移动同帧竞争导致 requestFocus 被覆盖（实测按 ↑ 无反应的原因）
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || wrapTo == null) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionUp -> {
                                if (isFirst) {
                                    val target = wrapTo
                                    scope.launch { target?.requestFocus() }
                                    true
                                } else {
                                    false
                                }
                            }

                            Key.DirectionDown -> {
                                if (isLast) {
                                    val target = wrapTo
                                    scope.launch { target?.requestFocus() }
                                    true
                                } else {
                                    false
                                }
                            }

                            else -> false
                        }
                    }
                },
        ) {
            // 选中页琥珀左条
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(4.dp)
                        .height(28.dp)
                        .background(TvColors.Accent),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = page.icon,
                    style = TvType.BodyLarge.copy(
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                    ),
                    color = if (selected) TvColors.Accent else textColor,
                    modifier = Modifier.width(28.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = page.label,
                    style = TvType.BodyMedium.copy(
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = textColor,
                )
            }
        }
    }
}

/** 焦点请求重试间隔（毫秒）。 */
private const val FOCUS_RETRY_MS = 50L

/** 焦点请求重试次数上限（50ms × 20 ≈ 1s；正常首次即成功，仅慢设备兜底）。 */
private const val FOCUS_RETRY_ATTEMPTS = 20
