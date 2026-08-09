package com.tudouni.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.data.ApiClient
import com.tudouni.tv.data.AuthStore
import com.tudouni.tv.ui.components.TvButton
import com.tudouni.tv.ui.components.TvDialog
import com.tudouni.tv.ui.components.PageHorizontalPadding
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.launch

/**
 * 设置页（对应设计方案 §6.7 简化版）：账号信息 + 服务器地址 + 退出登录。
 * 分组卡片列表布局，焦点上下移动。
 */
@Composable
fun SettingsScreen(
    username: String,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val authStore = remember { AuthStore(context) }
    val scope = rememberCoroutineScope()
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PageHorizontalPadding, vertical = 40.dp),
    ) {
        // 页标题（琥珀竖条 + 40sp）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(40.dp)
                    .background(TvColors.Accent)
            )
            Spacer(Modifier.width(14.dp))
            Text(text = "设置", style = TvType.PageTitle, color = TvColors.TextPrimary)
        }
        Spacer(Modifier.height(32.dp))

        // 分组：账号
        SettingsGroup(title = "账号") {
            SettingsRow(label = "当前账号", value = username.ifBlank { "未登录" })
            SettingsRow(label = "服务端", value = ApiClient.DEFAULT_SERVER)
            Spacer(Modifier.height(20.dp))
            TvButton(
                text = "退出登录",
                style = com.tudouni.tv.ui.components.TvButtonStyle.Secondary,
                onClick = { showLogoutConfirm = true },
            )
        }

        Spacer(Modifier.height(32.dp))

        // 分组：说明
        SettingsGroup(title = "关于") {
            SettingsRow(label = "版本", value = "0.1.0")
            Spacer(Modifier.height(12.dp))
            Text(
                text = "播放进度保存在服务端，换设备登录同一账号即可继续观看。",
                style = TvType.Caption,
                color = TvColors.TextTertiary,
            )
        }
    }

    if (showLogoutConfirm) {
        TvDialog(
            title = "退出登录",
            message = "退出后本机将清除登录信息，确认退出？",
            confirmText = "退出",
            danger = true,
            onConfirm = {
                showLogoutConfirm = false
                scope.launch {
                    authStore.logout()
                    ApiClient.configure(null)
                    onLogout()
                }
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TvColors.BgSurface, TvShapes.Card)
            .padding(28.dp),
    ) {
        Text(
            text = title,
            style = TvType.RowTitle.copy(fontSize = 26.sp),
            color = TvColors.Accent,
        )
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = TvType.BodyMedium, color = TvColors.TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = TvType.BodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            color = TvColors.TextPrimary,
        )
    }
}
