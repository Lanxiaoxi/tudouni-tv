package com.tudouni.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tudouni.tv.data.ApiClient
import com.tudouni.tv.data.AuthStore
import com.tudouni.tv.data.VideoItem
import com.tudouni.tv.ui.components.TvNavRail
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

    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }

    // 当前登录用户名（登录成功/启动恢复时赋值；主框架各页展示用）
    var username by remember { mutableStateOf("") }

    // 主框架当前页（进程重建后恢复上次所在页；enum 需转字符串存储）
    var mainPageName by rememberSaveable { mutableStateOf(NavPage.HOME.name) }
    val mainPage = NavPage.valueOf(mainPageName)

    // 启动：读取已保存的登录态（token 与 username 并行读，减少 Loading 时长）
    LaunchedEffect(Unit) {
        if (screen is Screen.Loading) {
            val (t, u) = combine(authStore.token, authStore.username) { token, name -> token to name }.first()
            if (!t.isNullOrEmpty()) {
                ApiClient.configure(t)
                username = u ?: ""
                screen = Screen.Main(mainPage)
            } else {
                screen = Screen.Login
            }
        }
    }

    fun goMain() {
        screen = Screen.Main(NavPage.valueOf(mainPageName))
    }

    when (val s = screen) {
        is Screen.Loading -> LoadingView()

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
            MainFrame(
                page = s.page,
                username = username,
                onPageChange = { p ->
                    mainPageName = p.name
                    screen = Screen.Main(p)
                },
                onOpenDetail = { item -> screen = Screen.Detail(item) },
                onPlay = { item, url, episodes, episodeIndex, resumeMs ->
                    screen = Screen.Player(item, url, episodes, episodeIndex, resumeMs)
                },
                onLogout = {
                    // 退出登录：清 token/用户名，回到登录页
                    username = ""
                    screen = Screen.Login
                },
            )
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

/**
 * 主框架：左侧导航栏 + 内容区。
 * 内容区用 SaveableStateHolder 按页面名隔离状态——切页/返回时各页焦点与滚动位置不丢（§4.2 焦点记忆）。
 */
@Composable
private fun MainFrame(
    page: NavPage,
    username: String,
    onPageChange: (NavPage) -> Unit,
    onOpenDetail: (VideoItem) -> Unit,
    onPlay: (VideoItem, String, List<String>, Int, Long) -> Unit,
    onLogout: () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        TvNavRail(currentPage = page, onSelect = onPageChange)
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
                    )
                }
            }
        }
    }
}

/** 导航分类 → /api/vodlist cat 参数。 */
private fun NavPage.toCat(): String? = when (this) {
    NavPage.MOVIE -> "movie"
    NavPage.SERIES -> "series"
    NavPage.ANIME -> "anime"
    NavPage.VARIETY -> "variety"
    else -> null
}

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(text = "加载中…")
        }
    }
}
