package com.tudouni.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType

/**
 * TV 确认弹窗（对应设计方案 §5.9）：
 * - 宽 720dp 居中、圆角 24dp、--bg-surface 底 + line-strong 描边 + 深遮罩
 * - M2 修复：打开后焦点落在「确认」主按钮（LaunchedEffect + FocusRequester），
 *   返回键关闭（Dialog 天然焦点隔离，关闭后焦点由 Compose 归还）
 */
@Composable
fun TvDialog(
    title: String,
    message: String? = null,
    confirmText: String = "确认",
    cancelText: String = "取消",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val confirmFocus = remember { FocusRequester() }
    // M2：弹窗打开后把焦点给确认主按钮
    LaunchedEffect(Unit) {
        confirmFocus.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .background(TvColors.BgSurface, TvShapes.Dialog)
                .border(1.dp, TvColors.LineStrong, TvShapes.Dialog)
                .padding(horizontal = 48.dp, vertical = 40.dp),
        ) {
            Text(
                text = title,
                style = TvType.PageTitle.copy(fontSize = 28.sp),
                color = if (danger) TvColors.Danger else TvColors.TextPrimary,
            )
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    style = TvType.BodyMedium,
                    color = TvColors.TextSecondary,
                )
            }
            Spacer(Modifier.height(40.dp))
            Row(Modifier.align(Alignment.End)) {
                TvButton(
                    text = cancelText,
                    style = TvButtonStyle.Secondary,
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(20.dp))
                TvButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = Modifier.focusRequester(confirmFocus),
                )
            }
        }
    }
}
