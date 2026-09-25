package com.tudouni.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tudouni.tv.data.ApiClient
import com.tudouni.tv.data.AuthStore
import com.tudouni.tv.data.HomePrefetch
import com.tudouni.tv.data.SettingsPreference
import com.tudouni.tv.data.VideoItem
import com.tudouni.tv.ui.components.SplashScreen
import com.tudouni.tv.ui.components.TvDialog
import com.tudouni.tv.ui.components.TvNavRail
import com.tudouni.tv.ui.components.UpdateFlow
import com.tudouni.tv.ui.navigation.NavPage
import com.tudouni.tv.ui.screens.CategoryScreen
import com.tudouni.tv.ui.screens.DetailScreen
import com.tudouni.tv.ui.screens.HistoryScreen
import com.tudouni.tv.ui.screens.HomeScreen
import com.tudouni.tv.ui.screens.LoginScreen
import com.tudouni.tv.ui.screens.PlayerScreen
import com.tudouni.tv.ui.screens.SearchScreen
import com.tudouni.tv.ui.screens.SettingsScreen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 屏幕状态机（手写导航，不用 navigation 库——TV 焦点恢复更可控，设计方案 §7.1）。
 *
 * 拓扑（对应 §3.2）：
 * - Login / Loading：全屏
 * - Main：左侧导航 + 内容区（Home/Category/Search/History/Settings 五页，SaveableStateHolder 保留各页焦点）
 * - Detail / Player：全屏覆盖（L3 深层页），返回回到原 Main 页
 */
sealed class Screen {
    object Loading : Screen()
    object Login : Screen()
    data class Main(val page: NavPage) : Screen()
    data class Detail(val item: VideoItem) : Screen()
    data class Player(
        val item: VideoItem,
        val url: String,
        val episodes: List<String>,
        val episodeIndex: Int,
        val resumePositionMs: Long,
    ) : Screen()
}

@Composable
fun App() {
    val context = LocalContext.current
    val authStore = remember { AuthStore(context) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }

    // 当前登录用户名（登录成功/启动恢复时赋值；主框架各页展示用）
    var username by remember { mutableStateOf("") }

    // 主框架当前页（进程重建后恢复上次所在页；enum 需转字符串存储）
    var mainPageName by rememberSaveable { mutableStateOf(NavPage.HOME.name) }
    val mainPage = NavPage.valueOf(mainPageName)

    // 初始焦点给导航栏当前页（默认首页）——一次性：首次进入主框架时请求，
    // 之后置 false，避免从详情/播放页返回时焦点被抢回导航栏
    var navInitialFocus by remember { mutableStateOf(true) }

    // 软件更新（2026-08-22）：设置页手动触发计数 + 自动检查一次性标记
    // （避免每次从详情/播放页返回主界面都重复自动检查）
    var updateCheckTrigger by remember { mutableIntStateOf(0) }
    var autoUpdateChecked by remember { mutableStateOf(false) }

    // 登录态失效（HTTP 401：token 过期或被服务端吊销）。
    // 失效广播由 ApiClient 的 OkHttp 拦截器统一发出，覆盖首页 / 分类 / 搜索 / 历史 /
    // 详情 / 换源 / 进度上报全部调用——这些页面原本只会提示「加载失败」或静默吞掉，
    // 而重试对已失效的 token 无效，用户只能自己猜到「设置 → 退出登录」。
    // - pending：收到过失效事件且还没提示（在详情/播放页发生也记着，回主框架再弹）
    // - consumed：本次登录会话内已提示过。失效 token 会让后续每个请求继续 401，
    //   若每个 401 都弹，「稍后」就形同虚设 → 每次登录只提示一次
    var authExpiredPending by remember { mutableStateOf(false) }
    var authExpiredConsumed by remember { mutableStateOf(false) }

    /** 收下这次失效提示：本次登录会话内不再重复弹（下次进入登录页时复位）。 */
    fun consumeAuthExpiry() {
        authExpiredPending = false
        authExpiredConsumed = true
    }

    // 清空本机凭证并回到登录页（与设置页「退出登录」同一套收尾：
    // 失效 token 留着只会让每个接口继续 401）。
    // 用 remember 固定 lambda 身份：它会经 CompositionLocal 下发给各页失败态的
    // 「重新登录」按钮，而 staticCompositionLocalOf 值变化会重组全部读取方，
    // 每次重组换新 lambda 会让内容树反复重组。
    val relogin: () -> Unit = remember {
        {
            authExpiredPending = false
            authExpiredConsumed = true
            scope.launch {
                authStore.logout()
                ApiClient.configure(null)
                username = ""
                mainPageName = NavPage.HOME.name
                screen = Screen.Login
            }
        }
    }

    // 启动：读取已保存的登录态（token 与 username 并行读，减少 Loading 时长）
    LaunchedEffect(Unit) {
        if (screen is Screen.Loading) {
            val (t, u) = combine(authStore.token, authStore.username) { token, name -> token to name }.first()
            if (!t.isNullOrEmpty()) {
                ApiClient.configure(t)
                username = u ?: ""
                // 开屏期间预拉首页首批数据（与 token 检查衔接，数据到位才进主页——
                // 开屏图一次到底，进入主页直接见内容；预拉失败则 HomeScreen 自行加载兜底）。
                // 限时 3 秒：没有超时的话 connect(10s)+read(20s) 会让开屏白等半分钟。
                withTimeoutOrNull(HOME_PREFETCH_TIMEOUT_MS) {
                    HomePrefetch.load(SettingsPreference(context).isContentFilterEnabled())
                }
                screen = Screen.Main(mainPage)
            } else {
                screen = Screen.Login
            }
        }
    }

    fun goMain() {
        screen = Screen.Main(NavPage.valueOf(mainPageName))
    }

    /** 退出登录（设置页确认弹窗与「登录已过期」共用）：清 token/用户名，回到登录页。 */
    fun doLogout() {
        username = ""
        screen = Screen.Login
    }

    // 收集登录态失效广播：任何页面的 401 都记一笔（在详情/播放页发生也保留，
    // 回到主框架后再提示）
    LaunchedEffect(Unit) {
        ApiClient.authExpired.collect {
            authExpiredPending = true
        }
    }

    // 进入登录页即视为已处理：清掉待办与标记，重新登录后若再过期仍能提示
    LaunchedEffect(screen) {
        if (screen is Screen.Login) {
            authExpiredPending = false
            authExpiredConsumed = false
        }
    }

    // 各页失败态的「重新登录」按钮经 LocalRelogin 取用同一动作——详情/播放等深层页
    // 不在 MainFrame 的参数链上，逐层传参会污染一层层签名
    CompositionLocalProvider(LocalRelogin provides relogin) {
        when (val s = screen) {
            is Screen.Loading -> SplashScreen()

            is Screen.Login -> LoginScreen(
                authStore = authStore,
                onLoginSuccess = { token, name ->
                    ApiClient.configure(token)
                    username = name
                    mainPageName = NavPage.HOME.name
                    screen = Screen.Main(NavPage.HOME)
                }
            )

            is Screen.Main -> {
                // 非首页页按返回键回首页；已在首页时返回键默认退出应用
                BackHandler(enabled = s.page != NavPage.HOME) {
                    mainPageName = NavPage.HOME.name
                    screen = Screen.Main(NavPage.HOME)
                }
                Box(Modifier.fillMaxSize()) {
                    MainFrame(
                        page = s.page,
                        username = username,
                        navInitialFocus = navInitialFocus,
                        onNavFocusConsumed = { navInitialFocus = false },
                        onPageChange = { p ->
                            mainPageName = p.name
                            screen = Screen.Main(p)
                        },
                        onOpenDetail = { item -> screen = Screen.Detail(item) },
                        onPlay = { item, url, episodes, episodeIndex, resumeMs ->
                            screen = Screen.Player(item, url, episodes, episodeIndex, resumeMs)
                        },
                        onLogout = { doLogout() },
                        onCheckUpdate = { updateCheckTrigger++ },
                    )
                    // 软件更新弹窗流程（覆盖主框架所有页面：自动检查 + 设置页手动触发）
                    UpdateFlow(
                        autoCheck = !autoUpdateChecked,
                        checkTrigger = updateCheckTrigger,
                        onAutoCheckConsumed = { autoUpdateChecked = true },
                    )
                    // 登录已过期提示：与更新流程同层（覆盖主框架各页），按钮焦点由 TvDialog 自行请求。
                    // 两个条件都必要——pending（有未处理的失效）且本次会话尚未提示过（consumed=false）
                    if (authExpiredPending && !authExpiredConsumed) {
                        TvDialog(
                            title = "登录已过期",
                            message = "登录凭证已失效，需要重新登录后才能继续使用。",
                            confirmText = "重新登录",
                            cancelText = "稍后",
                            onConfirm = relogin,
                            // 「稍后」只关提示、不强制登出（用户可能想先看完当前内容）；
                            // consumeAuthExpiry 保证同一批 401 不会反复弹窗
                            onDismiss = { consumeAuthExpiry() },
                        )
                    }
                }
            }

            is Screen.Detail -> DetailScreen(
                item = s.item,
                onBack = { goMain() },
                // M5：换源后 onPlay 携带最新 item（sourceCode/vodId 可能已变）
                onPlay = { newItem, url, episodes, episodeIndex, resumeMs ->
                    screen = Screen.Player(newItem, url, episodes, episodeIndex, resumeMs)
                },
            )

            is Screen.Player -> {
                BackHandler { goMain() }
                PlayerScreen(
                    item = s.item,
                    url = s.url,
                    episodes = s.episodes,
                    episodeIndex = s.episodeIndex,
                    resumePositionMs = s.resumePositionMs,
                    onBack = { goMain() },
                )
            }
        }
    }
}

/**
 * 主框架：左侧导航栏 + 内容区。
 * 内容区用 SaveableStateHolder 按页面名隔离状态——切页/返回时各页焦点与滚动位置不丢（§4.2 焦点记忆）。
 */
@Composable
private fun MainFrame(
    page: NavPage,
    username: String,
    navInitialFocus: Boolean,
    onNavFocusConsumed: () -> Unit,
    onPageChange: (NavPage) -> Unit,
    onOpenDetail: (VideoItem) -> Unit,
    onPlay: (VideoItem, String, List<String>, Int, Long) -> Unit,
    onLogout: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        TvNavRail(
            currentPage = page,
            onSelect = onPageChange,
            initialFocus = navInitialFocus,
            onFocusConsumed = onNavFocusConsumed,
        )
        val stateHolder = rememberSaveableStateHolder()
        stateHolder.SaveableStateProvider(page.name) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (page) {
                    NavPage.HOME -> HomeScreen(
                        username = username,
                        onOpenDetail = onOpenDetail,
                        onPlay = onPlay,
                        onOpenCategory = { cat -> onPageChange(cat) },
                    )

                    NavPage.MOVIE, NavPage.SERIES, NavPage.ANIME, NavPage.VARIETY -> CategoryScreen(
                        initialCat = page.toCat(),
                        onOpenDetail = onOpenDetail,
                    )

                    NavPage.HISTORY -> HistoryScreen(
                        onOpenDetail = onOpenDetail,
                        onPlay = onPlay,
                    )

                    NavPage.SEARCH -> SearchScreen(onOpenDetail = onOpenDetail)

                    NavPage.SETTINGS -> SettingsScreen(
                        username = username,
                        onLogout = onLogout,
                        onCheckUpdate = onCheckUpdate,
                    )
                }
            }
        }
    }
}

/** 开屏预拉首页数据的超时（毫秒）：避免后端慢时 connect(10s)+read(20s) 把开屏拖到半分钟。 */
private const val HOME_PREFETCH_TIMEOUT_MS = 3_000L

/** 导航分类 → /api/vodlist cat 参数。 */
private fun NavPage.toCat(): String? = when (this) {
    NavPage.MOVIE -> "movie"
    NavPage.SERIES -> "series"
    NavPage.ANIME -> "anime"
    NavPage.VARIETY -> "variety"
    else -> null
}
