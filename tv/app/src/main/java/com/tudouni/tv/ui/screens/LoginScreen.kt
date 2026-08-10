package com.tudouni.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.data.ApiClient
import com.tudouni.tv.data.AuthStore
import com.tudouni.tv.data.errorMessage
import com.tudouni.tv.ui.components.TvButton
import com.tudouni.tv.ui.components.TvButtonStyle
import com.tudouni.tv.ui.components.TvTextKeyboard
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.launch

/** 当前编辑的输入字段。 */
private enum class Field { Username, Password }

/**
 * 登录/注册页（对应设计方案 §5.9 登录变体，H1 改造：自研 TV 键盘）。
 * 用户名/密码两个字段卡上下切换（OK 选中），下方键盘输入到当前字段；
 * 密码以 ● 回显且隐藏拼音候选。登录成功后由 App 统一处理。
 */
@Composable
fun LoginScreen(
    authStore: AuthStore,
    onLoginSuccess: (token: String, username: String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var activeField by remember { mutableStateOf(Field.Username) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun doLogin(register: Boolean) {
        if (username.isBlank() || password.isBlank()) {
            error = "请填写用户名和密码"
            return
        }
        scope.launch {
            loading = true
            error = null
            try {
                ApiClient.configure(null)
                val resp = if (register) {
                    ApiClient.get().register(
                        mapOf("username" to username.trim(), "password" to password)
                    )
                } else {
                    ApiClient.get().login(
                        mapOf("username" to username.trim(), "password" to password)
                    )
                }
                if (resp.isSuccessful) {
                    val body = resp.body()
                    val data = body?.data
                    if (body != null && body.code == 0 && data != null) {
                        ApiClient.configure(data.token)
                        authStore.saveLogin(data.token, data.username)
                        onLoginSuccess(data.token, data.username)
                    } else {
                        error = body?.message ?: "登录失败"
                    }
                } else {
                    error = resp.errorMessage()
                }
            } catch (e: Exception) {
                error = "网络错误: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "土豆TV",
            style = TvType.DisplayTitle,
            color = TvColors.Accent,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "服务端 ${ApiClient.DEFAULT_SERVER}",
            style = TvType.BodyMedium,
            color = TvColors.TextTertiary,
        )
        Spacer(Modifier.height(36.dp))

        // 初始焦点：给用户名字段卡（Compose 不自动聚焦，否则整页方向键不工作）
        val usernameFieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { usernameFieldFocus.requestFocus() }

        // 字段选择：用户名 / 密码（方向键上下切换，OK 选中编辑目标）
        FieldCard(
            label = "用户名",
            value = username,
            password = false,
            focused = activeField == Field.Username,
            onClick = { activeField = Field.Username },
            modifier = Modifier.focusRequester(usernameFieldFocus),
        )
        Spacer(Modifier.height(14.dp))
        FieldCard(
            label = "密码",
            value = password,
            password = true,
            focused = activeField == Field.Password,
            onClick = { activeField = Field.Password },
        )

        Spacer(Modifier.height(24.dp))

        // H1：自研 TV 键盘（输入到当前字段）
        TvTextKeyboard(
            value = if (activeField == Field.Username) username else password,
            onValueChange = { v ->
                if (activeField == Field.Username) username = v else password = v
            },
            onSubmit = { doLogin(register = false) },
            password = activeField == Field.Password,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(26.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TvButton(
                text = if (loading) "登录中…" else "登 录",
                onClick = { doLogin(register = false) },
                enabled = !loading,
                modifier = Modifier.width(300.dp),
            )
            // U11：注册按钮统一用 TvButton Ghost，与全站体系一致
            TvButton(
                text = "注册新账号",
                style = TvButtonStyle.Ghost,
                onClick = { doLogin(register = true) },
                enabled = !loading,
                modifier = Modifier.width(300.dp),
            )
        }

        error?.let {
            Spacer(Modifier.height(18.dp))
            Text(
                text = it,
                color = TvColors.Danger,
                style = TvType.BodyMedium,
            )
        }
    }
}

/** 字段卡：显示标签 + 当前值（密码 ● 回显），可聚焦，OK 切换为编辑目标。 */
@Composable
private fun FieldCard(
    label: String,
    value: String,
    password: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val display = if (password) value.replace(Regex("."), "●") else value

    Box(
        modifier = modifier
            .width(900.dp)
            .height(64.dp)
            .background(if (focused || isFocused) TvColors.Accent else TvColors.BgElevated, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = TvType.BodyLarge.copy(fontSize = 22.sp),
                color = if (focused || isFocused) TvColors.AccentInk else TvColors.TextSecondary,
            )
            Spacer(Modifier.width(28.dp))
            Text(
                text = if (display.isEmpty()) "（空）" else display,
                style = TvType.BodyLarge.copy(fontSize = 24.sp),
                color = if (focused || isFocused) TvColors.AccentInk else TvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
