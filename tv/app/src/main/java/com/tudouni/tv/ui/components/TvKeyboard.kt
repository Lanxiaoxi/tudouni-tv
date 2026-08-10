package com.tudouni.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.data.VideoWordBank
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType

/**
 * TV 自研键盘（H1 修复）：不依赖系统 IME（Android TV 大多无软键盘/遥控器无法输入）。
 * - 布局：候选词行（拼音联想）+ 数字行 + 字母/符号页 + 功能行（空格/退格/清空/完成）
 * - 拼音候选：输入纯字母时按词库匹配（VideoWordBank），方向键选择候选，OK 替换输入
 * - 密码模式：隐藏拼音候选（密码框由外部以 ● 回显）
 * - 所有按键走 TvButton 同款焦点体系（scale 1.05 + 描边），D-pad 全键盘可达
 */
@Composable
fun TvTextKeyboard(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    enabled: Boolean = true,
    initialFocus: Boolean = false,
) {
    // 当前键页：false=字母，true=符号
    var symbolPage by remember { mutableStateOf(false) }

    // 初始焦点：页面进入时聚焦第一个键（否则整页方向键不工作）
    val firstKeyFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(initialFocus) {
        if (initialFocus) firstKeyFocus.requestFocus()
    }

    val isPinyinInput = !password && value.isNotEmpty() && value.all { it.isLetter() }
    val candidates = if (isPinyinInput) remember(value) { VideoWordBank.matchCandidates(value) } else emptyList()

    val keyWidth = 66.dp
    val keyHeight = 48.dp
    val keyGap = 8.dp

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // ---- 拼音候选行（可聚焦，OK 替换整个输入串） ----
        if (candidates.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(candidates.size, key = { "cand_$it" }) { i ->
                    TvChip(
                        text = candidates[i],
                        selected = false,
                        onClick = { onValueChange(candidates[i]) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ---- 数字行（两页共用）；第一个键挂初始焦点 ----
        KeyRow(
            keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            keyWidth = keyWidth,
            keyHeight = keyHeight,
            keyGap = keyGap,
            enabled = enabled,
            onKey = onValueChange,
            firstKeyFocus = firstKeyFocus,
        )

        Spacer(Modifier.height(keyGap))

        // ---- 字母页 / 符号页 ----
        if (symbolPage) {
            SymbolRows(keyWidth, keyHeight, keyGap, enabled, onValueChange)
        } else {
            LetterRows(keyWidth, keyHeight, keyGap, enabled, onValueChange)
        }

        Spacer(Modifier.height(keyGap))

        // ---- 功能行：模式切换 / 空格 / 退格 / 清空 / 完成 ----
        Row(horizontalArrangement = Arrangement.spacedBy(keyGap), verticalAlignment = Alignment.CenterVertically) {
            KeyButton(
                text = if (symbolPage) "ABC" else "#+=",
                width = keyWidth,
                height = keyHeight,
                enabled = enabled,
                onClick = { symbolPage = !symbolPage },
            )
            KeyButton(
                text = "空格",
                width = keyWidth * 3 + keyGap * 2,
                height = keyHeight,
                enabled = enabled,
                onClick = { onValueChange(value + " ") },
            )
            KeyButton(
                text = "←",
                width = keyWidth,
                height = keyHeight,
                enabled = enabled && value.isNotEmpty(),
                onClick = { onValueChange(value.dropLast(1)) },
            )
            KeyButton(
                text = "清空",
                width = keyWidth * 2 + keyGap,
                height = keyHeight,
                enabled = enabled && value.isNotEmpty(),
                onClick = { onValueChange("") },
            )
            KeyButton(
                text = "完成 ✓",
                width = keyWidth * 2 + keyGap,
                height = keyHeight,
                primary = true,
                enabled = enabled && value.isNotEmpty(),
                onClick = onSubmit,
            )
        }
    }
}

@Composable
private fun LetterRows(keyWidth: androidx.compose.ui.unit.Dp, keyHeight: androidx.compose.ui.unit.Dp, keyGap: androidx.compose.ui.unit.Dp, enabled: Boolean, onKey: (String) -> Unit) {
    KeyRow(listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"), keyWidth, keyHeight, keyGap, enabled, onKey)
    Spacer(Modifier.height(keyGap))
    Row(horizontalArrangement = Arrangement.spacedBy(keyGap)) {
        Spacer(Modifier.width((keyWidth + keyGap) / 2))
        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L").forEach { c ->
            KeyButton(c, keyWidth, keyHeight, enabled, onClick = { onKey(c) })
        }
        Spacer(Modifier.width((keyWidth + keyGap) / 2))
    }
    Spacer(Modifier.height(keyGap))
    Row(horizontalArrangement = Arrangement.spacedBy(keyGap)) {
        Spacer(Modifier.width(keyWidth + keyGap))
        listOf("Z", "X", "C", "V", "B", "N", "M").forEach { c ->
            KeyButton(c, keyWidth, keyHeight, enabled, onClick = { onKey(c) })
        }
        Spacer(Modifier.width(keyWidth + keyGap))
    }
}

@Composable
private fun SymbolRows(keyWidth: androidx.compose.ui.unit.Dp, keyHeight: androidx.compose.ui.unit.Dp, keyGap: androidx.compose.ui.unit.Dp, enabled: Boolean, onKey: (String) -> Unit) {
    KeyRow(listOf(".", ",", "?", "!", "-", "_", "@", "#", "&", "("), keyWidth, keyHeight, keyGap, enabled, onKey)
    Spacer(Modifier.height(keyGap))
    KeyRow(listOf(")", "/", ":", ";", "'", "\"", "+", "=", "*", "%"), keyWidth, keyHeight, keyGap, enabled, onKey)
    Spacer(Modifier.height(keyGap))
    Row(horizontalArrangement = Arrangement.spacedBy(keyGap)) {
        Spacer(Modifier.width(keyWidth + keyGap))
        listOf("$", "~", "^", "\\", "|", "[", "]", "{" ).forEach { c ->
            KeyButton(c, keyWidth, keyHeight, enabled, onClick = { onKey(c) })
        }
        Spacer(Modifier.width(keyWidth + keyGap))
    }
}

@Composable
private fun KeyRow(
    keys: List<String>,
    keyWidth: androidx.compose.ui.unit.Dp,
    keyHeight: androidx.compose.ui.unit.Dp,
    keyGap: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onKey: (String) -> Unit,
    firstKeyFocus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(keyGap)) {
        keys.forEachIndexed { i, c ->
            KeyButton(
                text = c,
                width = keyWidth,
                height = keyHeight,
                enabled = enabled,
                onClick = { onKey(c) },
                modifier = if (i == 0 && firstKeyFocus != null) {
                    Modifier.focusRequester(firstKeyFocus)
                } else {
                    Modifier
                },
            )
        }
    }
}

/** 键盘按键：TvButton 同款焦点体系的小尺寸变体（高 48dp、字 20sp）。 */
@Composable
private fun KeyButton(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1f,
        animationSpec = tween(
            durationMillis = if (isFocused) 200 else 120,
            easing = FastOutSlowInEasing,
        ),
        label = "keyScale",
    )

    val bg = when {
        !enabled -> TvColors.BgElevated.copy(alpha = 0.5f)
        primary -> TvColors.Accent
        else -> TvColors.BgElevated
    }
    val contentColor = when {
        !enabled -> TvColors.TextTertiary
        primary -> TvColors.AccentInk
        isFocused -> TvColors.TextPrimary
        else -> TvColors.TextSecondary
    }
    val borderModifier = if (isFocused && enabled) {
        Modifier.border(2.dp, if (primary) Color.White else TvColors.Accent, shape)
    } else if (!primary) {
        Modifier.border(1.dp, TvColors.Line, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .then(borderModifier)
            .background(bg, shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TvType.BodyMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            color = contentColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
