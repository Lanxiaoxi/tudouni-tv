# TudouniTV · Android TV 客户端

基于现有 LibreTV 后端（FastAPI）的 **Android TV 原生客户端**，后端零改动。
技术栈：Kotlin + Jetpack Compose + Media3 ExoPlayer + Retrofit。

## 环境要求

| 项 | 要求 |
|---|---|
| Android Studio | Ladybug（2024.2.1）或更新 |
| Gradle JVM | **JDK 17–23**（Gradle 8.10.2 不支持 JDK 24/25） |
| 设备 | Android TV 盒子 / 电视，**Android 8.0（API 26）及以上**（minSdk 26） |
| 后端 | 线上 `https://tv.lanxi.me`（客户端固定，改地址见 `ApiClient.DEFAULT_SERVER`） |

> **本机已配置**：`gradle.properties` 中 `org.gradle.java.home` 指向
> `C:\Users\XPS\.jdks\jdk-21.0.12+8`（Temurin 21，位于 `%USERPROFILE%\.jdks`）。
> Android Studio 新版自带 JBR 为 **JDK 25**，与 Gradle 8.10.2 不兼容，工程内已强制使用 JDK 21，
> 打开即 sync 通过。**换机器时**：删除该行，在
> `Settings → Build Tools → Gradle → Gradle JVM` 选择 ≤23 的 JDK。

## 打开方式

1. Android Studio → `File > Open`，选择本目录（`tv/`），等待 Gradle Sync。
2. 首次 Sync 会自动下载 **Gradle 8.10.2**（已配腾讯镜像，见下）与依赖（已预下载缓存）。
   - `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 已指向
     `https://mirrors.cloud.tencent.com/gradle/gradle-8.10.2-bin.zip`（官方源在本机超时）；
     海外/CI 可改回 `https://services.gradle.org/distributions/gradle-8.10.2-bin.zip`
3. Sync 通过后即可 `Run`。首次会提示安装 Android SDK（同意即可）。

## 运行与侧载

```bash
# 连接电视盒子（ADB 网络调试，与盒子同一局域网）
adb connect <盒子IP>:5555
# 安装调试包
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或直接在 Android Studio 里 `Build > Build APK(s)` 后把 APK 拷到盒子安装。

## 使用

1. 打开 App，直接输入**用户名密码**登录（首次可点"注册"）；后端地址固定为
   `https://tv.lanxi.me`（`data/ApiClient.kt` 的 `DEFAULT_SERVER`，无需用户配置）。
2. 首页为横向焦点卡片流：遥控器左右移动、OK 进入详情。
3. 详情页选择集数播放（ExoPlayer 原生播 HLS，直连资源站，无浏览器 CORS 限制）。

## 对接的后端接口（全部保持后端不变）

| 接口 | 说明 |
|---|---|
| `POST /api/auth/login` / `register` | body `{username,password}` → `data.token`（Bearer 鉴权） |
| `GET /api/items?offset=&limit=` | 首页列表，分批加载（默认 500/批），`total/has_more` 分页 |
| `GET /api/detail?id=&source=` | 详情：`episodes[]`（m3u8 地址）+ `videoInfo` |

注意：`/api/items` 成功码是 `code=0`，`/api/detail` 成功码是 `code=200`，客户端均已兼容。

## 架构

```
app/src/main/java/com/tudouni/tv/
├── MainActivity.kt       入口（Compose 宿主）
├── ui/
│   ├── App.kt            屏幕状态机（Login/Home/Detail/Player，未引入 nav 库）
│   ├── LoginScreen.kt    登录/注册（后端地址固定）
│   ├── HomeScreen.kt     首页列表（焦点卡片流 + 分页加载）
│   ├── DetailScreen.kt   详情 + 选集
│   ├── PlayerScreen.kt   ExoPlayer HLS 播放
│   └── theme/Theme.kt    TV 深色主题
└── data/
    ├── ApiClient.kt      Retrofit 单例（固定后端地址 + 拦截器附加 Bearer token）
    ├── AuthStore.kt      DataStore 持久化（token/用户名）
    ├── Models.kt         接口数据模型
    └── TudouniApi.kt     后端接口定义
```

## 后续扩展点（骨架已留位）

- **搜索**：`GET /api/search?wd=`（Retrofit 接口已备，需加搜索页）
- **分类**：`GET /api/vodlist?cat=`（按类型浏览）
- **播放进度记忆**：`PUT /api/history`（现 `videoProgress` 仅存于网页端 localStorage）
- **遥控器优化**：弱盒性能（ExoPlayer `trackSelectionParameters` 降清晰度）
- 当前播放器返回键直接回首页（详情跳转链路为骨架简化），后续可引入 nav 栈

## 已知限制（骨架阶段）

- 无分类/搜索/历史页面（接口已通，页面待加）
- 封面来自后端 `/api/items` 的 `vod_pic`（含 `/covers/` 本地封面路径，Coil 直接加载）
- 明文流量已在 Manifest 全局开启（`usesCleartextTraffic`），生产可收敛为 `networkSecurityConfig` 白名单
