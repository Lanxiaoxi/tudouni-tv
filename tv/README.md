# TudouniTV · Android TV 客户端

基于现有 LibreTV 后端（FastAPI）的 **Android TV 原生客户端**，后端除软件更新接口
（`/api/app/version`、`/api/app/download`，见下文）外零改动。
技术栈：Kotlin + Jetpack Compose + Media3 ExoPlayer + Retrofit。

## 环境要求

| 项 | 要求 |
|---|---|
| Android Studio | Ladybug（2024.2.1）或更新 |
| Gradle JVM | **JDK 17–23**（Gradle 8.10.2 不支持 JDK 24/25） |
| 设备 | Android TV 盒子 / 电视，**Android 5.0（API 21）及以上**（minSdk 21） |
| 后端 | 线上 `https://tv.lanxi.me`（客户端固定，改地址见 `ApiClient.DEFAULT_SERVER`） |

> **本机已配置**：`gradle.properties` 中 `org.gradle.java.home` 指向
> `C:\Users\XPS\.jdks\jdk-21.0.12+8`（Temurin 21，位于 `%USERPROFILE%\.jdks`）。
> Android Studio 新版自带 JBR 为 **JDK 25**，与 Gradle 8.10.2 不兼容，工程内已强制使用 JDK 21，
> 打开即 sync 通过。**换机器时**：删除该行，在
> `Settings → Build Tools → Gradle → Gradle JVM` 选择 ≤23 的 JDK。

## 兼容范围与适配

| 范围 | 说明 |
|---|---|
| 最低系统 | **Android 5.0（API 21）**——覆盖国产老盒子（运营商定制/国产 5.1/7.1 大量在用） |
| 签名方案 | **v1 (JAR) + v2 双签**。Android 7.0 以下与部分国产 ROM 安装器只认 v1，AGP 在 `minSdk >= 24` 时默认关 v1，需显式 `enableV1Signing = true` |
| ABI | arm64-v8a / armeabi-v7a / x86 / x86_64。**不支持** MIPS / 纯 armeabi（AGP 8.x 已不支持这两类），此类盒子无解需换设备 |
| 关键适配 | `AppUpdater.kt` 内置 `Build.VERSION.SDK_INT >= O` 版本分支：低版本跳 `Settings.ACTION_SECURITY_SETTINGS`（系统级"未知来源"开关），不用 8.0 才有的 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` |

调试盒子（adb 接入后）：

```bash
adb shell getprop ro.build.version.sdk        # 必须 ≥ 21
adb shell getprop ro.product.cpu.abilist      # 必须含 armeabi-v7a 或 arm64-v8a
adb install -r -t <apk>                        # 看真实错误码，盒子 UI 只显示「解析包错误」
```

**维护提示：**

- **改 `minSdk` 后必须跑 `./gradlew lintRelease`**，不是 `lintVitalRelease`——前者会扫 `NewApi` 误调用。本项目 minSdk 26→21 时曾发现 `canRequestPackageInstalls` / `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 需 API 26，已用 `Build.VERSION` 分支处理。
- **Media3 大量 `@UnstableApi` API**。kotlin 的 `@OptIn` / `@file:OptIn` **只能过编译器、lint 的 `UnsafeOptInUsageError` 不认**，且 opt-in 要求会沿调用链向上传播（标注函数 → 调用方 → …），逐级标注会污染整个 UI 层签名。已通过 `build.gradle.kts` 的 `lint { warning += "UnsafeOptInUsageError" }` 降级（与 Google Media3 demo 同处理）。
- **模拟器端到端验证**：`Television_4K` AVD（API 31）→ install → am start → screencap。**全部步骤必须放在同一条 Bash 命令内**，否则模拟器进程随 `run_in_background` 任务结束被回收，下次启动时 `adb` 会报 "no devices"。

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

## 软件更新与发版流程

TV 端内置「检查更新」（设置 → 关于，且启动时自动静默检查一次）：请求后端
`/api/app/version` 拿到最新版本信息，用 `versionCode` 对比当前版本，有新版则弹窗
提示用户下载安装（下载 APK → 调起系统安装器）。

**发版步骤（每次出新版本）：**

1. **升版本号**：`app/build.gradle.kts` → `versionCode` +1、`versionName` 更新。
2. **构建**：Android Studio `Build > Build APK(s)`（或 `./gradlew assembleRelease`），
   产物在 `app/build/outputs/apk/release/app-release.apk`。
3. **上传 APK**：把 release APK 传到服务器 `backend/tudounitv.apk`
   （仓库 `.gitignore` 的 `*.apk` 已忽略该文件，不提交 git）。
4. **更新版本信息**：编辑服务器上 `backend/app_version.json`：

   ```json
   {
     "latest_version": "0.2.0",
     "latest_code": 2,
     "download_url": "/api/app/download",
     "notes": "本次更新内容…",
     "force": false
   }
   ```

   `latest_code` 必须大于客户端当前 `BuildConfig.VERSION_CODE` 才会提示更新；
   `notes` 展示在更新弹窗中；`force` 为强制更新标记（字段已预留，客户端暂未处理）。
5. **部署**：后端 `GET /api/app/version` 每次实时读 `app_version.json`，改文件即生效，
   无需重启服务；客户端下次检查更新即可看到新版。

**注意：**

- **签名一致性**：升级 APK 必须与已装 APK **同签名**，否则系统安装器拒绝安装。
  本项目 release 复用 debug keystore（`build.gradle.kts` 的 `signingConfig`），
  务必保存好该 keystore，换机器/CI 别弄丢。
- **首次安装权限**：Android 8+ 首次更新需在系统设置允许「安装未知应用」
  （应用内会引导到该设置页）；授权后重新点「检查更新」即可走下载安装。
- **本地测试**：把 `backend/app_version.json` 的 `latest_code` 临时调大（如 `999`）
  即可触发更新提示，测完记得改回。

## 使用

1. 打开 App，直接输入**用户名密码**登录（首次可点"注册"）；后端地址固定为
   `https://tv.lanxi.me`（`data/ApiClient.kt` 的 `DEFAULT_SERVER`，无需用户配置）。
2. 首页为横向焦点卡片流：遥控器左右移动、OK 进入详情。
3. 详情页选择集数播放（ExoPlayer 原生播 HLS，直连资源站，无浏览器 CORS 限制）。

## 对接的后端接口

| 接口 | 说明 |
|---|---|
| `POST /api/auth/login` / `register` | body `{username,password}` → `data.token`（Bearer 鉴权） |
| `GET /api/items?offset=&limit=` | 首页列表，分批加载（默认 500/批），`total/has_more` 分页 |
| `GET /api/detail?id=&source=` | 详情：`episodes[]`（m3u8 地址）+ `videoInfo` |
| `GET /api/app/version` | 软件更新：最新版本信息（`latest_code` / `download_url` / `notes`，无需鉴权） |
| `GET /api/app/download` | 软件更新：APK 安装包下载（无需鉴权） |

注意：`/api/items` 成功码是 `code=0`，`/api/detail` 成功码是 `code=200`，客户端均已兼容。

## 登录态失效（token 过期）处理

后端 `require_token` 在 token 过期/被吊销时统一返回 `401 {"code":401,"message":"凭证无效或已过期"}`
（有效期由后端 `TOKEN_TTL_DAYS` 控制，默认 7 天）。这类失败**重试无用**——用的是同一个失效
token，因此客户端不引导「重试」，而是引导重新登录：

| 环节 | 位置 | 行为 |
|---|---|---|
| 识别 | `ApiClient` 的 OkHttp 拦截器 | 响应 401 且本机持有 token → 广播 `ApiClient.authExpired`（未带 token 的 401 不广播，避免登录页密码错误时误跳） |
| 文案归一 | `Models.pageErrorMessage()` | 业务接口 401 → 「登录已过期，请重新登录」，不带「网络错误」前缀（`userFacingError()` 同理）。**登录接口仍用 `errorMessage()`**，那里的 401 是密码错误 |
| 全局提示 | `App.kt` | 主框架内弹出「登录已过期」+「重新登录 / 稍后」；从详情/播放页返回后也会补弹；每次登录会话只提示一次（否则「稍后」形同虚设） |
| 页内出口 | `LoadFailedState` + `LocalRelogin` | 首页/分类/搜索/历史/详情的失败态在 401 时把「重试」换成「重新登录」按钮；`LocalRelogin` 由 `App` 提供，避免逐层传参 |

「重新登录」与「稍后」的区别：前者清 token/用户名并跳登录页；后者只关提示、不强制登出
（用户可能想先看完当前内容），但后续请求仍会 401。

## 播放页焦点架构（2026-08-25 重构）

**问题**：此前播放页同时存在两套焦点系统——Media3 `PlayerView` 自带控制条（播放/暂停、
进度条、快进快退）在**原生 View 焦点系统**里，而返回/全屏/上下集按钮在 **Compose 焦点系统**里。
两者之间转移焦点时，Compose 只能通过 `FocusFinder` 按屏幕矩形几何关系猜最近邻，
方向键落点不可控（焦点在「返回」按右键会跳到几何上最近的「退出全屏」，按下键跳到「上一集」）。

**方案**：`useController = false` 关闭原生控制条，改用自绘 `PlayerControlsBar`（Compose），
全页只剩一套焦点。控制条布局：

```
────────────────────────────────────────  ← 进度条 + 时间（纯展示，不可聚焦）
  [⏮ 上一集?]  [▶/❚❚]  [⏪ 退10秒]  [⏩ 进10秒]  [⏭ 下一集?]
```

上下集并入按钮行，使导航退化为一维：左右在行内移动，上下在「按钮行 ↔ 顶栏」间移动。
进度条**不可聚焦**（纯展示），快进/快退由两侧按钮承担——避免出现「能聚焦但按 OK 无反应」的死焦点。

**随之删除的三处历史补丁**（都是为弥合两套焦点而加的）：

| 补丁 | 原用途 | 替代方案 |
|---|---|---|
| `OnKeyListener` 视图层兜底 | 焦点丢失时按键到不了预览 handler | 隐藏态焦点锚点 `hiddenAnchorFocus`（1dp 可聚焦占位节点） |
| 唤醒键 KeyUp 配对吞键 | 跨系统焦点抢占导致 KeyUp 误触按钮 | 仍需吞唤醒键的 KeyUp（控制条在 KeyDown 时组合出来，KeyUp 会被 clickable 当点击） |
| `showController()` 联动唤醒 | 原生控制条有自己的 5s 隐藏且无法自醒 | 原生控制条已不存在 |

同时去掉了「隐藏按钮用 `alpha(0f)` 但仍在组合中」的做法——隐藏时直接**不组合**，
否则隐藏的按钮仍参与焦点搜索，方向键可能落到看不见的按钮上。

## 架构

```
app/src/main/java/com/tudouni/tv/
├── MainActivity.kt       入口（Compose 宿主）
├── player/
│   └── PlayerController.kt  ExoPlayer 封装（播放/暂停/seek/错误与缓冲状态）
├── ui/
│   ├── App.kt            屏幕状态机（Login/Main/Detail/Player，未引入 nav 库）
│   ├── LocalRelogin.kt   「重新登录」动作的 CompositionLocal 通道（各页失败态共用）
│   ├── navigation/       NavPage 顶层导航枚举
│   ├── components/       通用组件（PosterCard / TvButton / TvChip / EpisodeGrid /
│   │                     TvNavRail / TvKeyboard / TvDialog / PlayerControlsBar /
│   │                     FocusableSurface / ContentRow / States / UpdateFlow）
│   ├── screens/          Login / Home / Category / Detail / Player / History / Search / Settings
│   └── theme/            TV 深色主题（Color / Shape / Type / Theme）
└── data/
    ├── ApiClient.kt      Retrofit 单例（固定后端地址 + 拦截器附加 Bearer token + 401 广播）
    ├── TudouniApi.kt     后端接口定义
    ├── Models.kt         接口数据模型 + 401 文案归一化 + 历史脏数据容错解析
    ├── TvRepository.kt   进度记忆/历史/搜索历史的读写封装
    ├── AuthStore.kt      DataStore 持久化（token/用户名）
    ├── SettingsPreference.kt  SharedPreferences（分级过滤/自动连播开关）
    ├── HomePrefetch.kt / CategoryCache.kt   列表内存缓存（TTL 100 分钟）
    ├── ContentFilter.kt  内容分级过滤（16 个关键词，与 Web 端一致）
    ├── SourceSwitcher.kt 换源（与后端 sites.py 源列表对齐）
    ├── AppUpdater.kt     软件更新（检查/下载/调起安装器）
    └── VideoWordBank.kt  拼音搜索词库
```

## 后续扩展点

- **遥控器优化**：弱盒性能（ExoPlayer `trackSelectionParameters` 降清晰度）
- 播放页返回键直接回主界面（详情跳转链路为简化实现），后续可引入 nav 栈
- **强制更新**：后端 `app_version.json` 的 `force` 字段已预留，客户端尚未处理

## 已知限制

- 封面来自后端 `/api/items` 的 `vod_pic`（含 `/covers/` 本地封面路径，Coil 直接加载）
- 明文流量已在 Manifest 全局开启（`usesCleartextTraffic`），生产可收敛为 `networkSecurityConfig` 白名单
