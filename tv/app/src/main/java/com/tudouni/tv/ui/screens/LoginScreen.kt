package com.tudouni.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tudouni.tv.data.ApiClient
import com.tudouni.tv.data.AuthStore
import com.tudouni.tv.data.errorMessage
import com.tudouni.tv.ui.components.TvButton
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.launch

/**
 * 登录/注册页（对应设计方案 §5.9 登录变体，TV 软键盘输入）。
 * 登录成功后由 App 统一处理：配置 token、存 DataStore、进首页。
 */
@Composable
fun LoginScreen(
    authStore: AuthStore,
    onLoginSuccess: (token: String, username: String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
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
            .verticalScroll(rememberScrollState()),
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
        Spacer(Modifier.height(48.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.width(620.dp),
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(620.dp),
        )

        Spacer(Modifier.height(40.dp))

        TvButton(
            text = if (loading) "登录中…" else "登 录",
            onClick = { doLogin(register = false) },
            enabled = !loading,
            modifier = Modifier.width(620.dp),
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = { doLogin(register = true) },
            enabled = !loading,
        ) {
            Text("没有账号？注册一个", style = TvType.BodyMedium)
        }

        error?.let {
            Spacer(Modifier.height(20.dp))
            Text(
                text = it,
                color = TvColors.Danger,
                style = TvType.BodyMedium,
            )
        }
    }
}
