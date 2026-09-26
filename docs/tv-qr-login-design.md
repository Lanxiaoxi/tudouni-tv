# 扫码登录 TV 端 · 设计与实现说明

> 状态：**后端与前端确认页已实现并通过验证；TV 端已完成代码改造，待用户在 Android Studio
> 编译验收**（按 AGENT.md，TV 端只做静态审阅、不执行构建）。
> 撰写背景：TV 端原先只能用遥控器在自研虚拟键盘上逐字输入用户名密码（`LoginScreen.kt` 的
> `TvTextKeyboard`），体验很差；改为「手机网页端扫码 → 授权登录 TV 端」。

## 1. 缘起与结论

动手前代码库**没有任何扫码 / 设备码登录实现**：

- 全仓搜索 `qrcode|qr_code|扫码|扫一扫|scan`，命中仅来自 `libs/tailwindcss.min.js`、
  `libs/hls.min.js`、`tv/app/build/reports/lint-results-release.html` 等无关内容；
- `libs/` 只有 `artplayer.min.js` / `hls.min.js` / `sha256.min.js` / `tailwindcss.min.js`，
  没有二维码库；
- 后端依赖只有 `fastapi` / `httpx` / `uvicorn`（`backend/pyproject.toml`），无二维码相关依赖。

但现有鉴权架构几乎是为这个场景准备好的，新增功能**不需要改动任何现有登录逻辑**（见 §2）。

采用策略：**设备码授权（Device Code Grant）**，即 GitHub / YouTube TV / Netflix 那一套：
TV 出码 → 手机扫码确认 → TV 轮询领取 token。

### 1.1 本次落地的文件清单

**后端（新增 1、修改 3）**

| 文件 | 内容 |
|---|---|
| `backend/app/device.py` | **新增**：start / poll / confirm / deny 四个端点实现 + 按 IP 限流 |
| `backend/app/db.py` | `device_codes` 建表、启动清理、7 个数据访问函数 |
| `backend/app/main.py` | 注册 4 条路由（前两条无鉴权） |
| `backend/app/config.py` | 4 个配置项 |

**前端（新增 3，未改动任何既有 js/css）**

| 文件 | 内容 |
|---|---|
| `tv.html` | **新增**：手机端确认页 |
| `css/tv-login.css` | **新增**：复用 `design.css` 设计令牌的页面样式 |
| `js/pages/tv-login.js` | **新增**：确认/登录/拒绝逻辑 |

**TV 端（新增 1、修改 4）**

| 文件 | 内容 |
|---|---|
| `tv/.../ui/components/QrCode.kt` | **新增**：zxing core 生成二维码位图组件 |
| `tv/.../ui/screens/LoginScreen.kt` | 接入扫码模式（默认）+ 账号密码降级 |
| `tv/.../data/TudouniApi.kt` | 加 `deviceStart` / `devicePoll` |
| `tv/.../data/Models.kt` | 加 `DeviceStartData` / `DevicePollData` |
| `tv/app/build.gradle.kts` | 加 `com.google.zxing:core:3.5.3` |

> 前端「版本号约定」说明：本次只在 `js/`、`css/` 下**新增**文件，未修改任何已有资源，
> 因此 `index.html` 的既有 `?v=N` **无需** bump（约定针对的是「已引用资源的缓存键失效」）。
> 新文件由 `tv.html` 自带 `?v=1` 引用。**将来若改动 `proxy-auth.js` / `api.js` 等既有共享文件，
> 仍需按约定 bump `index.html` 中对应的 `?v=N`。**

## 2. 现状依据（可行性来自哪些既有事实）

### 2.1 token 天然支持多端并存

- `backend/app/db.py` 的 `tokens` 表以 `token` 为主键、`user_id` 为外键（`ON DELETE CASCADE`），
  并有 `idx_tokens_user` 索引；
- `db.create_token(user_id, ttl_seconds)` 每次 `secrets.token_hex(32)` **插入一条新行**，
  不存在「一个用户只有一个 token」的约束；
- 因此「给 TV 签发一个新 token」不会踢掉手机上已有的登录态。多端并存是现成语义。

### 2.2 登录接口无需鉴权、CORS 全开

- `backend/app/main.py` 中 `POST /api/auth/login`、`POST /api/auth/register`、
  `POST /api/auth/logout`、`GET /api/health`、`app.include_router(update.router)`（`/api/app/*`）
  是仅有的不带 `Depends(auth.require_token)` 的端点；
- CORS 中间件为 `allow_origins=["*"]` + `allow_methods=["*"]` + `allow_headers=["*"]`；
- 认证契约极简（`backend/app/auth.py`）：`{username, password}` →
  `{token, expires_in, user_id, username}`；token 是服务端可查表的随机串，
  `require_token` → `db.resolve_token` 校验。

### 2.3 TV 端只有一个「登录成功」落点

`tv/app/src/main/java/com/tudouni/tv/ui/App.kt`：

```kotlin
is Screen.Login -> LoginScreen(
    authStore = authStore,
    onLoginSuccess = { token, name ->
        ApiClient.configure(token)
        username = name
        mainPageName = NavPage.HOME.name
        screen = Screen.Main(NavPage.HOME)
    }
)
```

只要扫码流程最终把「服务端签发的 token + username」喂给这个回调，
`ApiClient` 的 OkHttp 拦截器（附加 `Authorization: Bearer`）、401 广播 `authExpired`、
开屏 `HomePrefetch` 全部零改动。`AuthStore.saveLogin(token, username)` 写 DataStore 也是现成的。

### 2.4 TV 端没有 push 能力与 URL scheme

- `tv/app/src/main/AndroidManifest.xml` 只有 `INTERNET` 与 `REQUEST_INSTALL_PACKAGES` 权限；
  `MainActivity` 的 intent-filter 只有 `MAIN` / `LEANBACK_LAUNCHER`，**没有自定义 scheme 或 App Links**；
- 项目未接 FCM。
- 结论：「手机点链接直接唤起电视 App」走不通，**TV 端只能轮询**。

## 3. 协议设计：设备码授权

三个角色：TV（持 `device_code`）、手机浏览器（持用户登录态）、后端（撮合）。

```
TV 未登录 ──POST /api/auth/device/start──▶ 后端生成 device_code + user_code
   ▲                                          返回 {device_code, user_code, verify_url, expires_in, interval}
   │ 屏幕上显示二维码（内容 = verify_url，绝对地址，已含 user_code）+ 人读码兜底
   │
   │ 轮询 POST /api/auth/device/poll {device_code}
   │      ← {status: pending | confirmed{token,username} | consumed | denied | expired}
   │
手机扫码 ──▶ GET /tv.html?code=XXXX（手机端已登录 → 显示「确认在电视上登录」按钮）
             ──POST /api/auth/device/confirm {user_code} + 手机自己的 Bearer token──▶ 绑定 user_id
```

二维码内容就是后端返回的 `verify_url`（`{base}/tv.html?code={user_code}`），
TV 端不自行拼接——域名由部署方通过 `DEVICE_VERIFY_BASE_URL` 或反代头决定，客户端不该猜。

### 3.1 为什么是两条码

| 码 | 谁持有 | 用途 |
|---|---|---|
| `device_code` | 只有 TV | 轮询凭据，不出现在屏幕上、不进二维码 |
| `user_code` | 展示给用户（二维码 + 人读） | 手机上用它发起确认 |

即使有人拍到二维码，也只能「发起确认」，拿不到 token——**token 只在 TV 轮询命中
`confirmed` 时才签发**。这是设备码模式相比「二维码里直接塞一次性 token」的核心优势。

### 3.2 状态机

```
pending ──(手机 confirm)──▶ confirmed ──(TV poll 领取)──▶ consumed
   │                            │
   ├──(超过 TTL)──▶ expired     └──(手机主动拒绝)──▶ denied
```

- `confirmed → consumed` 必须是**一次性**的：poll 领到 token 的同时把状态置 `consumed`，
  重复 poll 返回 `consumed` 且不再签发（防重放）。
- `expired` / `denied` 由 TV 端展示「已过期，请重新获取二维码」/「已拒绝」。

## 4. 后端改动

### 4.1 建表（`backend/app/db.py` 的 `init_db()` 内，幂等）

```sql
CREATE TABLE IF NOT EXISTS device_codes (
    device_code TEXT PRIMARY KEY,               -- 32 字节随机，TV 持有
    user_code   TEXT UNIQUE NOT NULL,           -- 8 位人读码，大写字母+数字（去除易混字符）
    status      TEXT NOT NULL DEFAULT 'pending',-- pending/confirmed/consumed/denied
    user_id     INTEGER REFERENCES users(id) ON DELETE CASCADE,
    attempts    INTEGER NOT NULL DEFAULT 0,     -- confirm 尝试次数（防爆破）
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_device_codes_expires ON device_codes(expires_at);
```

顺带在 `init_db()` 内清理过期行（`DELETE FROM device_codes WHERE expires_at < now`），
与现有「存量库迁移」写法保持一致。

### 4.2 新增 `backend/app/device.py`

放四个 handler，风格对齐 `auth.py` / `userdata.py`：

- `start(request)` → 生成两码、插行、返回
  `{device_code, user_code, verify_url, expires_in, interval}`；
- `poll(device_code)` → 查状态；`confirmed` 时 `db.create_token(user_id, TOKEN_TTL_SECONDS)`
  并把行置 `consumed`，返回 `{status:"confirmed", token, username, user_id, expires_in}`；
- `confirm(user_code, request, user_id)` → 校验码存在、未过期、未消费，写入 `user_id` 并置 `confirmed`；
- `deny(user_code, request, user_id)` → 用户主动拒绝，置 `denied`（仅对 `pending` 生效）。

`poll` **统一返回 HTTP 200 + `status` 字段**（`pending` / `confirmed` / `consumed` /
`denied` / `expired`），让 TV 端用一条分支处理所有情况，不必按 HTTP 状态码分支；
`confirm` 的错误才用状态码区分：404 无效 / 410 过期 / 409 已被使用 / 429 限流。

`user_code` 生成：从去掉 `0/O/1/I/L` 的 31 个字符里取 8 位（约 31^8 ≈ 8.5e11 空间）。
人读码**不设行级尝试上限**——见下方说明。

#### 防爆破为什么落在 IP 限流而不是 `attempts` 计数

实现时发现原设计有个逻辑漏洞：**猜错的 `user_code` 在库里根本不存在**，直接返回 `not_found`，
既没有行可以累加 `attempts`，也没有行可以置 `denied`——「尝试超限后作废」的分支实际不可达，
防不住爆破。所以真实的限流做在端点层：`_rate_limit_ok()` 按 `X-Forwarded-For` 首段取客户端 IP，
60 秒滑动窗口内超过 `DEVICE_CONFIRM_MAX_ATTEMPTS` 次即返回 429。
`attempts` 列保留下来记录该码被确认的次数，仅供排查。

（同理，`confirm` 内部用 `UPDATE ... WHERE status='pending'` 的写法保证并发幂等。）

### 4.3 路由（`backend/app/main.py`）

| 方法 | 路径 | 鉴权 |
|---|---|---|
| `POST` | `/api/auth/device/start` | **无**（TV 未登录时调用） |
| `POST` | `/api/auth/device/poll` | **无**（凭 `device_code`） |
| `POST` | `/api/auth/device/confirm` | `Depends(auth.require_token)`（手机端已登录） |
| `POST` | `/api/auth/device/deny` | `Depends(auth.require_token)`（手机端已登录） |

前两条与 `/api/auth/login` 归入同一类「无需鉴权」端点；
`confirm` / `deny` 复用 `require_token` 拿 `user_id`，天然拿到「是哪台手机、哪个账号在授权」。

### 4.4 配置（`backend/app/config.py`）

```python
DEVICE_CODE_TTL_SECONDS = int(os.getenv("DEVICE_CODE_TTL", "300"))            # 5 分钟
DEVICE_POLL_INTERVAL_SECONDS = int(os.getenv("DEVICE_POLL_INTERVAL", "3"))    # 建议轮询间隔
DEVICE_CONFIRM_MAX_ATTEMPTS = int(os.getenv("DEVICE_CONFIRM_MAX_ATTEMPTS", "5"))  # 每 IP 每分钟
DEVICE_VERIFY_BASE_URL = os.getenv("DEVICE_VERIFY_BASE_URL", "").rstrip("/")   # 二维码地址前缀
```

`DEVICE_VERIFY_BASE_URL` 是原设计漏掉、实现时补上的：二维码内容是**绝对地址**，
留空时后端按 `X-Forwarded-Proto` / `X-Forwarded-Host`（其次 `Host`）推导；
反代配置不标准时推导结果可能不对（扫出来打不开），此时显式设成
`https://tv.lanxi.me` 即可。扫码拿到的 token 沿用 `TOKEN_TTL_SECONDS`（默认 7 天），不另设。

## 5. TV 端改动（`tv/`）

> 按 AGENT.md：Kotlin/Gradle 只做静态审阅，**不执行任何构建命令**，最终由用户在 AS 中 sync + 编译验证。

| 文件 | 改动 |
|---|---|
| `data/TudouniApi.kt` | 加 `@POST("/api/auth/device/start")`、`@POST("/api/auth/device/poll")` |
| `data/Models.kt` | 加 `DeviceStartData` / `DevicePollData` 数据类（参考 `LoginResult` 的写法） |
| `ui/screens/LoginScreen.kt` | 默认进入扫码模式：二维码 + 人读码 + 状态文案 + 「重新获取」/「改用账号密码登录」 |
| `ui/App.kt` | **不改**（扫码成功仍走 `onLoginSuccess`） |
| `app/build.gradle.kts` | 只加 `implementation("com.google.zxing:core:3.5.3")` |

**实际实现与原设计的差异（重要）：**

1. 二维码没有「自己画到 Compose Canvas」，而是用 `QRCodeWriter` 直接编码成 **Bitmap**，
   再用 `Image` 显示。少写一套逐模块绘制代码，且 `FilterQuality.None`（最近邻缩放）
   能保住黑白边界锐利——双线性插值会把二维码糊掉。
2. 新增了 `ui/components/QrCode.kt` 组件（原设计未列），把「编码 + 出错兜底 + 白底固定」
   收在一处。
3. `ui/App.kt` **确实未改动**：扫码成功仍走原有的 `onLoginSuccess` 回调。

要点（均已落实）：

1. **二维码生成只依赖 `com.google.zxing:core`**，实际锁定 `3.5.3`（已核验该版本为纯 JAR、
   仅 test 作用域依赖 junit，minSdk 21 可用）。**没有**引 `zxing-android-embedded` / CameraX：
   那是给手机扫码用的，TV 盒子普遍无摄像头，加相机权限纯属多余。
2. **轮询用组合内的协程**：`LaunchedEffect(mode, scanAttempt) { while (isActive) { delay(...); poll() } }`，
   离开登录页或切到账号密码模式随组合自动取消，无需手工管理生命周期。
3. **轮询按 `expires_in` 收敛**（`deadline` 判断），不无限转。
   注意 `ApiClient` 的 OkHttp 是 `connectTimeout(10s)` + `readTimeout(20s)`，
   单次请求可能很久，故用绝对时间而非计数判断超时。
4. **轮询前 `ApiClient.configure(null)`**：否则拦截器会带上可能已过期的旧 token。
   （`start` / `poll` 本就无需鉴权，带旧凭证没有收益。）
5. 手动输入用户名密码的路径**保留**为降级方案，用 `TvChip` 双 Tab 在「扫码登录 / 账号密码」间切换，
   默认落在扫码。账号密码那套 UI 与键盘逻辑一行未改。

实现时踩到并修掉的两个坑：

- **不能用 `runCatching` 包轮询**：它会把 `CancellationException` 一起捕获，
  吞掉协程取消信号（离开登录页时 `LaunchedEffect` 要能被正常取消）。
  改成 `try / catch (CancellationException) { throw } / catch (Exception) { null }`。
- **`DevicePollData.status` 声明为可空**：Gson 绕过构造器默认值，
  字段缺失时会写入 `null`；若声明为非空 `String`，null 会在 `when` 比对处留下隐患。

## 6. 前端改动（手机网页端）

已按原设计的**第一种**方式落地：新增独立确认页 `tv.html` + `js/pages/tv-login.js`
（`watch.html` 那种「meta refresh 跳转」模式不适用，本页需要交互）。
路径选了 `/tv.html` 而非 `/tv`：`deploy/nginx.conf.example` 是
`try_files $uri $uri/ /index.html`，不存在的无扩展名路径会回退到**首页**，而 `.html` 能直接命中。

### 6.1 实际流程（比原设计多一个分支）

原设计只有「已登录→确认 / 未登录→弹窗登录」两条路径。实现时发现
`js/core/password.js` 的全局登录弹窗会把整页顶掉，且它的弹窗流程以 `location.reload()`
收尾，在「扫码确认」这个上下文里很别扭。因此**本页不加载 `password.js`**，
改为自己渲染登录表单，于是多出「无 `code` 参数」这一支：

1. **无 `code`**：显示输入框，让用户手输电视上显示的 8 位确认码（扫码失败时的兜底入口）。
2. **有 `code` 但手机未登录**：显示本页自带的用户名/密码表单；
   登录成功后**直接自动确认**，省掉一次点击。
3. **有 `code` 且手机已登录**：用 `/api/me` 校验 token 是否仍有效（本地有 token ≠ 服务端认），
   通过则展示确认码让用户与电视屏幕核对 → 「确认登录」/「不是我操作的」（`deny`）。
4. **完成后**：成功 / 已拒绝 / 确认码失效 三种终态，各给一个明确的下一步按钮
   （返回首页，或重新输入确认码）——而不是把用户留在错误提示上。

进页面即从地址栏 `history.replaceState` 清掉 `?code=`，避免用户把带码链接分享出去。

### 6.2 复用的现成能力

- `js/core/proxy-auth.js` 已导出
  `window.ProxyAuth.login / register / logout / getToken / setToken / clearToken / getCurrentUsername`；
- `js/core/api.js` 的 `window.Api` 封装 `/api/*`，非 2xx 时抛出 `Error(后端 message)`，
  中文错误文案可直接透传给用户。
- 已核验拦截器边界：`proxy-auth.js` 把 `url.startsWith('/api/auth')` 的请求视为
  **auth 端点**（401 时既不弹窗也不清 token，把响应原样交回调用方），
  所以 `device/confirm` 的 404/410/409/429 都能被本页正常捕获展示，不会被拦截器搅乱。
  而 `/api/me` 不属于 auth 端点且未登录时会被拦截器短路成 401，正好用来判断登录态。

### 6.3 样式

新增 `css/tv-login.css`，直接复用 `design.css` 的令牌（`--accent` / `--bg-surface` /
`.form-input` / `.act-btn`），不另造一套配色。只引用 `design.css?v=9`（与 `index.html` 现值一致），
未改动该文件。

## 7. 安全与约束

- **一次性 + 短 TTL + 按 IP 限流**是安全基础，而不是靠二维码内容保密。
  `confirmed` 只能被 poll 消费一次（`UPDATE ... WHERE status='confirmed'` 原子领取）；
  `user_code` 的爆破防护按调用方 IP 做限流（原因见 §4.2），TTL 5 分钟；
  码失效有明确终态，不会无限重试。
- **域名与跨域**：TV 端 `ApiClient.DEFAULT_SERVER` 固定为 `https://tv.lanxi.me`，
  而 `js/core/config.js` 的 `SITE_CONFIG.url` 是 `https://libretv.is-an.org`。
  `deploy/nginx.conf.example` 显示这类子域名是「前端静态文件 + `/api/` 反代同域托管」，
  因此二维码指向 `https://tv.lanxi.me/tv.html?code=...` 最省事（后端会按
  `DEVICE_VERIFY_BASE_URL` → `X-Forwarded-*`/`Host` 的优先级推导出这个地址）；
  若确认页放在另一个域名，`confirm` 就是跨域调用——当前 `allow_origins=["*"]` 能过
  （用的是 Header 而非 cookie），但**生产若把 CORS 收窄成白名单，别漏掉这个域名**。
- **不改动现有登录**：`/api/auth/login`、`/api/auth/register`、
  `LoginScreen` 的账号密码输入路径全部保留；扫码只是「把服务端签发的 token 投递到 TV」
  的额外通道。
- **仍是明文 HTTP 传 token**，与 README「重要声明」一致：生产环境务必 HTTPS。

## 8. 实施与验证状态

| 阶段 | 状态 |
|---|---|
| 1. 后端（建表 + `device.py` + 4 条路由 + 4 个配置项） | ✅ 已实现 |
| 2. 后端端到端验证 | ✅ 已通过（39/39 项，见下） |
| 3. 前端确认页（`tv.html` + `css` + `js`） | ✅ 已实现，JS 语法与 id 一致性已校验 |
| 4. TV 端（依赖 + 接口/模型 + 二维码组件 + `LoginScreen`） | ⏳ 代码已改完，**待 AS 编译验收** |
| 5. TV 端与手机端真机联调 | ⏳ 待做（需你在 AS 编译后实测） |

### 8.1 后端验证怎么做的

用 ASGI 内存客户端（`TestClient`，不绑端口、不起真实服务）搭了一个临时验证脚本，
指向**独立临时数据库**（`LIBRETV_DB` 环境变量），跑完即删。
覆盖 **39 项断言、全部通过**，包括：

- 设备码格式（64 位 hex / 8 位人读码 / 无 `0O1IL` 易混字符）、`verify_url` 拼接与 Host 推导；
- 未登录 confirm 返回 401；
- 完整链路：start → pending → confirm → poll 拿到 token；
- **新 token 与手机端 token 不同且都有效**，`/api/me`、`/api/items` 均可访问（多端并存）；
- 重复 poll 返回 `consumed` 且**不再签发 token**；
- 重复 confirm 返回 409、未知码返回 404、过期码 confirm 返回 410、过期后 poll 返回 `expired`；
- deny 流程 → poll 返回 `denied`；
- 缺 `device_code` 返回 400、未知 `device_code` 返回 `expired`；
- 同 IP 连续 confirm 触发 429 限流。

事后只读检查了生产库 `backend/data.db`：**未新增 `device_codes` 表、无测试用户混入**，
确认验证确实跑在临时库上、没有污染真实数据。

### 8.2 TV 端验证到哪一步（以及哪一步没做）

按 AGENT.md，**没有执行任何 Gradle 构建**。已做的静态审阅：

- 4 个 Kotlin 文件括号/圆括号配对平衡；
- 新增符号（`LoginMode` / `ScanState` / `ScanLoginSection` / `ScanFallback` /
  `DeviceStartData` / `DevicePollData` / `STATUS_*` / `deviceStart` / `devicePoll`）定义与引用闭合；
- 所有新用到的 import 已补齐（含 `mutableIntStateOf`、`TextAlign`、`CancellationException`）；
- 已核验 `com.google.zxing:core:3.5.3` 的 POM：纯 JAR、仅 test 作用域依赖 junit，
  minSdk 21 可用。

**尚未验证**（需要你在 Android Studio 做）：编译通过、真机/模拟器上二维码可被手机扫出、
轮询进主页、以及下面的联调清单。

### 8.3 联调验收清单

后端侧已由 §8.1 的自动化验证覆盖（对应下面标注「已验」的项）：

- [x] 手机已登录 → 确认后 TV 收到 `confirmed`（接口层已验，UI 待实测）
- [x] TV 拿到 token 后 `/api/items` 正常拉数据（接口层已验）
- [x] 两次 poll 同一 `device_code`：第二次拿不到新 token（已验）
- [x] 超时未确认 → `expired`（已验，TV 端 UI 展示待实测）
- [x] 手机端拒绝 → `denied`（已验）
- [x] 校验扫码新登录不影响手机端原有登录态（多 token 并存，已验）
- [x] 未登录无法 confirm（401，已验）
- [x] 超频 confirm 被限流（429，已验）
- [ ] TV `start` 拿到码，屏幕二维码与 `user_code` 一致、**手机能真的扫出来**
- [ ] 手机未登录 → 本页登录 → 登录成功后自动确认
- [ ] 手动用户名密码登录路径仍可用（TV 端 Tab 切换 + Web 端弹窗）
- [ ] 老盒子（低分辨率/Android 5.x）上二维码与确认码可读

## 9. 遗留与后续可选项

- **确认页的最终域名/路径**：目前按「与后端同源 + `/tv.html`」实现，
  部署时若前端与后端不同源，需相应调整 `DEVICE_VERIFY_BASE_URL` 与 CORS 白名单。
- **「已授权设备」列表与远程登出**：后端 `tokens` 表已具备按 `user_id` 查询的基础，
  但缺 `device` 标识列；要做的话需要给 `tokens` 加列并记录来源（如 `tv` / `web`）。
- **二维码失效的自动续期**：目前过期后由用户点「刷新二维码」；
  也可做成倒计时结束自动重新 `start`（当前刻意没做，避免无人值守时反复请求后端）。
