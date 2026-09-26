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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudouni.tv.data.ApiClient
import com.tudouni.tv.data.AuthStore
import com.tudouni.tv.data.DevicePollData
import com.tudouni.tv.data.DeviceStartData
import com.tudouni.tv.data.errorMessage
import com.tudouni.tv.ui.components.QrCodeImage
import com.tudouni.tv.ui.components.TvButton
import com.tudouni.tv.ui.components.TvButtonStyle
import com.tudouni.tv.ui.components.TvChip
import com.tudouni.tv.ui.components.TvTextKeyboard
import com.tudouni.tv.ui.theme.TvColors
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    // ---------- 扫码登录（设备码授权） ----------

    var mode by remember { mutableStateOf(LoginMode.Scan) }
    var scanState by remember { mutableStateOf<ScanState>(ScanState.Loading) }
    // 刷新二维码：递增即重启下面的 LaunchedEffect，重新申请设备码并重新开始轮询
    var scanAttempt by mutableIntStateOf(0)

    // 轮询协程随 [mode] / [scanAttempt] 变化重启，离开登录页随组合自动取消——
    // 不必手工管理 Job，也不会在切到账号密码模式后继续打后端。
    LaunchedEffect(mode, scanAttempt) {
        if (mode != LoginMode.Scan) return@LaunchedEffect

        scanState = ScanState.Loading
        // 清掉可能残留的旧 token：start/poll 本就无需鉴权，带上过期凭证只会白挨 401。
        // 失效会话下 App 进登录页前已 configure(null)，这里再兜一次底。
        ApiClient.configure(null)

        val startResp = try {
            ApiClient.get().deviceStart()
        } catch (e: Exception) {
            scanState = ScanState.Error("网络错误: ${e.message}")
            return@LaunchedEffect
        }
        val startData = startResp.body()?.data
        if (!startResp.isSuccessful || startResp.body()?.code != 0 || startData == null) {
            scanState = ScanState.Error(
                if (startResp.isSuccessful) (startResp.body()?.message ?: "无法获取登录二维码")
                else startResp.errorMessage()
            )
            return@LaunchedEffect
        }
        scanState = ScanState.Waiting(startData)

        // 轮询节奏与有效期一律以后端返回为准（interval / expires_in），不在客户端写死。
        var intervalMs = (startData.interval.takeIf { it > 0 } ?: 3L) * 1000
        val deadline = System.currentTimeMillis() + startData.expiresIn * 1000
        while (isActive && System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            // 单次网络抖动不终止整个流程：拿不到响应就等下一个周期再试。
            // 注意不能用 runCatching——它连 CancellationException 一起捕获，会吞掉协程取消信号
            // （离开登录页时页面的 LaunchedEffect 要能被正常取消）。
            val poll = try {
                ApiClient.get().devicePoll(mapOf("device_code" to startData.deviceCode)).body()?.data
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue

            poll.interval?.takeIf { it > 0 }?.let { intervalMs = it * 1000 }

            when (poll.status) {
                DevicePollData.STATUS_PENDING -> Unit  // 继续等

                DevicePollData.STATUS_CONFIRMED -> {
                    val token = poll.token
                    if (token.isNullOrEmpty()) {
                        scanState = ScanState.Error("登录凭证异常，请重新获取二维码")
                        return@LaunchedEffect
                    }
                    scanState = ScanState.Confirmed
                    // 与账号密码登录同一套收尾：存凭证 → 由 App 切主页
                    ApiClient.configure(token)
                    authStore.saveLogin(token, poll.username ?: "")
                    onLoginSuccess(token, poll.username ?: "")
                    return@LaunchedEffect
                }

                DevicePollData.STATUS_DENIED -> {
                    scanState = ScanState.Denied
                    return@LaunchedEffect
                }

                // expired：码不存在/已过期；consumed：本次凭证已被领走（重复轮询）
                DevicePollData.STATUS_EXPIRED, DevicePollData.STATUS_CONSUMED -> {
                    scanState = ScanState.Expired
                    return@LaunchedEffect
                }

                else -> Unit  // 未知状态：当作仍在等待，避免因后端新增状态而中断
            }
        }
        if (isActive) scanState = ScanState.Expired
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
        Spacer(Modifier.height(28.dp))

        // 默认扫码：遥控器上输用户名密码成本很高，扫码是主路径，账号密码保留为降级方案。
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvChip(
                text = "扫码登录",
                selected = mode == LoginMode.Scan,
                onClick = { mode = LoginMode.Scan },
            )
            TvChip(
                text = "账号密码",
                selected = mode == LoginMode.Manual,
                onClick = { mode = LoginMode.Manual },
            )
        }

        Spacer(Modifier.height(30.dp))

        if (mode == LoginMode.Scan) {
            ScanLoginSection(
                state = scanState,
                onRefresh = { scanAttempt++ },
                onSwitchToManual = { mode = LoginMode.Manual },
            )
        } else {
            // 初始焦点：给用户名字段卡（Compose 不自动聚焦，否则整页方向键不工作）
            val usernameFieldFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { usernameFieldFocus.requestFocus() }

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

/** 登录方式：扫码（默认，遥控器输入成本高）/ 账号密码（降级方案）。 */
private enum class LoginMode { Scan, Manual }

/** 扫码登录状态机（与后端 device/poll 的 status 对应）。 */
private sealed interface ScanState {
    /** 正在向后端申请设备码。 */
    object Loading : ScanState

    /** 已拿到设备码，等手机端确认。 */
    data class Waiting(val data: DeviceStartData) : ScanState

    /** 手机端已确认。App 会立刻切到主页，这里只是瞬时展示。 */
    object Confirmed : ScanState

    /** 用户在手机上点了「不是我操作的」。 */
    object Denied : ScanState

    /** 二维码已过期或已失效，需要重新获取。 */
    object Expired : ScanState

    /** 连设备码都没申请到（网络/服务端问题）。 */
    data class Error(val message: String) : ScanState
}

/**
 * 扫码登录区：展示二维码与确认码，并在失效/被拒后给出明确的下一步。
 *
 * 只负责展示——申请设备码与轮询（含超时）都在调用方的 LaunchedEffect 里，
 * 由它随组合生命周期自动取消。
 */
@Composable
private fun ScanLoginSection(
    state: ScanState,
    onRefresh: () -> Unit,
    onSwitchToManual: () -> Unit,
) {
    Column(
        modifier = Modifier.width(900.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            is ScanState.Loading -> {
                CircularProgressIndicator(color = TvColors.Accent, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(20.dp))
                Text("正在获取登录二维码…", style = TvType.BodyLarge, color = TvColors.TextSecondary)
            }

            is ScanState.Waiting -> {
                QrCodeImage(content = state.data.verifyUrl, sizeDp = 280)
                Spacer(Modifier.height(22.dp))
                Text(
                    text = "用手机扫码，或在手机浏览器打开确认页",
                    style = TvType.BodyLarge,
                    color = TvColors.TextSecondary,
                )
                Spacer(Modifier.height(20.dp))
                Text("确认码", style = TvType.Caption, color = TvColors.TextTertiary)
                Spacer(Modifier.height(6.dp))
                // 人读码兜底：扫码不成功时可在手机上手动输入，与手机展示的码核对
                Text(
                    text = state.data.userCode,
                    style = TvType.DisplayTitle.copy(fontSize = 44.sp, letterSpacing = 8.sp),
                    color = TvColors.Accent,
                )
                Spacer(Modifier.height(18.dp))
                Text("等待手机确认…", style = TvType.BodyMedium, color = TvColors.TextTertiary)
                Spacer(Modifier.height(26.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvButton(
                        text = "刷新二维码",
                        style = TvButtonStyle.Secondary,
                        onClick = onRefresh,
                        compact = true,
                        fontSize = 20.sp,
                    )
                    TvButton(
                        text = "改用账号密码登录",
                        style = TvButtonStyle.Ghost,
                        onClick = onSwitchToManual,
                        compact = true,
                        fontSize = 20.sp,
                    )
                }
            }

            is ScanState.Confirmed -> {
                CircularProgressIndicator(color = TvColors.Success, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(20.dp))
                Text("登录成功，正在进入…", style = TvType.BodyLarge, color = TvColors.Success)
            }

            is ScanState.Denied -> ScanFallback(
                message = "手机端已拒绝本次登录。\n如果不是你本人操作，建议修改账号密码。",
                onRefresh = onRefresh,
                onSwitchToManual = onSwitchToManual,
            )

            is ScanState.Expired -> ScanFallback(
                message = "二维码已过期。\n请在手机上重新扫描新生成的二维码。",
                onRefresh = onRefresh,
                onSwitchToManual = onSwitchToManual,
            )

            is ScanState.Error -> ScanFallback(
                message = state.message,
                onRefresh = onRefresh,
                onSwitchToManual = onSwitchToManual,
            )
        }
    }
}

/** 扫码不可用（过期/被拒/出错）时的统一兜底：重新获取 + 走账号密码。 */
@Composable
private fun ScanFallback(
    message: String,
    onRefresh: () -> Unit,
    onSwitchToManual: () -> Unit,
) {
    Text(
        text = message,
        style = TvType.BodyLarge,
        color = TvColors.TextSecondary,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(28.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TvButton(
            text = "重新获取二维码",
            onClick = onRefresh,
            compact = true,
            fontSize = 20.sp,
        )
        TvButton(
            text = "改用账号密码登录",
            style = TvButtonStyle.Secondary,
            onClick = onSwitchToManual,
            compact = true,
            fontSize = 20.sp,
        )
    }
}
