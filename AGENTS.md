# AGENTS.md — LibreTV 开发指南（AI 编码代理须知）

该项目安卓部分代码开发时候只进行简单语法检查，不要执行编译，我在AS自行编译测试。

## 项目概览

LibreTV（TudouniTV）是一个前后端分离的视频聚合搜索播放平台：

- **前端**：纯静态页面（HTML + CSS + 原生 JS），根目录 `index.html` / `player.html` / `watch.html` / `about.html`，脚本在 `js/core`（公共模块）与 `js/pages`（页面逻辑），样式在 `css/`，第三方库在 `libs/`（hls.js / artplayer / tailwind / sha256）。
- **后端**：Python 3.13 + FastAPI（`backend/`，uv 管理依赖），聚合第三方苹果 CMS V10 资源站，SQLite 存储用户 / 历史 / 资源镜像表。
- **安卓 TV 客户端**：Kotlin + Jetpack Compose + Media3 ExoPlayer + Retrofit，位于 `tv/` 目录。

## 核心规则（必须遵守）

### 安卓端（`tv/` 目录）

1. **只做简单语法检查，绝不执行编译/构建/打包**（不跑 `gradlew`、`assembleDebug`、`assembleRelease`、`./gradlew` 等任何构建命令）。构建、运行、安装、测试全部由用户在 Android Studio 中自行完成。
2. 修改 Kotlin / Gradle 文件时，仅做静态审阅（类型、引用、括号配对、导入、API 调用正确性），并向用户说明改动内容，提示用户在 AS 中 sync + 编译验证。
3. 不要随意改动 `tv/local.properties`、`tv/gradle.properties` 中与本机 JDK 路径相关的配置（当前指向 `C:\Users\XPS\.jdks\jdk-21.0.12+8`，Gradle 8.10.2 不支持 JDK 24/25）。
4. 发版流程（版本号、`app_version.json`、签名等）只提供说明，不代为执行。

### 前端静态资源版本号（`js/`、`css/`）

**只要改了 `js/` 或 `css/` 下的任何文件，必须同步 bump `index.html` 里对应资源的 `?v=N` 查询参数**（+1）。这是硬性约定，否则线上会出现「HTML 是新的、JS 是旧的」的缓存错位问题（Nginx + Cloudflare + PWA Service Worker 均以 URL 含 v 参数为缓存键）。

改完前端后应检查：`git diff index.html` 确认对应 `?v=N` 已 +1。

### 数据源配置同步

数据源定义在 **`backend/app/sites.py`** 与 **`js/config.js` / `js/customer_site.js`** 各有一份，两端必须同步增删，不能只改一边。

## 目录结构速览

```
LibreTV/
├── index.html / player.html / watch.html / about.html   # 前端页面
├── css/                      # 样式
├── js/core/                  # 公共基础模块（config / api / proxy-auth / password / ui …）
├── js/pages/                 # 页面逻辑（app / index-page / player / discovery …）
├── libs/                     # 第三方库
├── tv/                       # Android TV 客户端（Kotlin + Compose + Media3）
├── backend/
│   ├── app/                  # FastAPI 后端（main / config / sites / auth / userdata / db / search / detail / vodlist / home / hotrank / iqiyi / youku / tencent / cache / sync / proxy / textutil / security）
│   ├── pyproject.toml        # uv 管理依赖
│   └── data.db               # SQLite（运行时生成，勿提交）
├── deploy/                   # nginx 反代示例 + systemd 服务示例
├── manifest.json / service-worker.js   # PWA
└── tools/                    # 辅助脚本（如 download_covers.py）
```

## 后端开发约定

- 依赖用 `uv` 管理：`cd backend && uv sync`；开发运行：`uv run uvicorn app.main:app --host 127.0.0.1 --port 9797`（`SERVE_STATIC=true` 时后端同时挂载前端）。
- `backend/data.db` 是运行时生成文件，**不要提交**。
- 后端不读 `.env`，全部通过环境变量配置（见 README 环境变量表）。
- 除 `/api/health` 外所有接口需要 `Authorization: Bearer <token>`。

## 常规行为准则

1. **改代码前先读**：动手修改任何文件前先 read 该文件，不要凭假设改。
2. **小步改动**：每次改动范围尽量小、可读性好，不顺手重构无关代码。
3. **不要运行完整服务**：除非用户明确要求，不要启动后端 / 构建安卓工程 / 起本地服务器占用端口。
4. **提交信息**：遵循现有仓库风格（中文描述，简洁说明改动内容）。
5. 修改 README 中描述过的行为时，如架构 / 接口 / 环境变量有变化，应同步更新 README。

## 环境

- Python >= 3.13，uv 包管理器（后端）
- Android Studio Ladybug（2024.2.1）+ JDK 17–23（安卓端，由用户自行编译）
