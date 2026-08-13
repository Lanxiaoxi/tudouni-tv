# TudouniTV

> 自由观影，畅享精彩 — 基于苹果 CMS V10 资源站聚合的视频搜索与播放平台。

## 项目简介

TudouniTV 是一个前后端分离的视频聚合搜索工具。后端使用 **Python FastAPI** 聚合多个第三方资源站的搜索 / 详情 / 列表接口，前端为纯静态页面，通过浏览器直接播放 HLS 视频流。

- **视频流直连资源站**，不经服务器代理，带宽消耗极低
- 服务器仅代理元数据（搜索结果、详情、封面图），并维护 **SQLite 数据库**（多用户体系 + 资源镜像表 + 缓存）
- **多用户注册登录**：观看历史 / 搜索历史 / 偏好设置按用户隔离，换设备登录自动同步
- **资源镜像表**：定时从资源站拉取索引，首页 / 分类 / 搜索本地查库，大幅减少对上游的实时请求
- **热播榜单板块**：后端拉取爱奇艺 / 优酷 / 腾讯热播榜片名，映射到资源站聚合搜索结果，首页独立渲染（失败静默隐藏；内存缓存 5 天 + 数据库条目并集兜底）
- 支持多源聚合搜索、换源测速、进度记忆、自动连播
- 暗色 / 亮色双主题，响应式布局
- **Android TV 客户端**（`tv/` 目录）：Kotlin + Jetpack Compose + Media3 ExoPlayer，对接同一后端 API

## 架构

```
LibreTV/
├── index.html          # 首页（发现 / 分类 / 搜索）
├── player.html         # 播放页
├── watch.html          # 重定向跳转页
├── about.html          # 关于页
├── css/                # 样式（styles / design / player / watch）
├── js/
│   ├── core/           # 公共基础模块（config / api / proxy-auth / password / ui …）
│   └── pages/          # 页面逻辑（app / index-page / player / player-page / discovery …）
├── libs/               # 第三方库（hls.js / artplayer / tailwind / sha256）
├── tv/                 # Android TV 客户端（Kotlin + Jetpack Compose + Media3）
├── backend/            # Python FastAPI 后端
│   ├── app/
│   │   ├── main.py     # 入口：路由 + 静态挂载 + lifespan 启动后台同步任务
│   │   ├── config.py   # 环境变量配置
│   │   ├── sites.py    # 数据源定义 + CMS URL 构建（与前端 config.js 同步）
│   │   ├── auth.py     # 注册 / 登录 / 登出，token 绑定用户
│   │   ├── userdata.py # 用户数据端点（me / settings / history / search-history）
│   │   ├── db.py       # SQLite 数据层（users / tokens / history / search_history / videos / hot_rank_items）
│   │   ├── search.py   # 三级聚合搜索（TTL 缓存 → 镜像表 → 实时）
│   │   ├── detail.py   # 视频详情（TTL 缓存）
│   │   ├── vodlist.py  # 列表 / 分类（查镜像表，含源可达性测试）
│   │   ├── home.py     # 首页数据（查镜像表）
│   │   ├── hotrank.py  # 热播榜通用框架（拉片名 → 聚合搜索 → 内存缓存 + 库兜底 + 预热，5 天 TTL）
│   │   ├── iqiyi.py    # 爱奇艺热播榜片名抓取（复用 hotrank 框架）
│   │   ├── youku.py    # 优酷热播榜片名抓取（解析榜单页 __INITIAL_DATA__）
│   │   ├── tencent.py  # 腾讯热播榜片名抓取（POST getPage 频道接口）
│   │   ├── cache.py    # 进程内 TTL 缓存（惰性过期 + LRU）
│   │   ├── sync.py     # 资源镜像表轮转同步任务
│   │   ├── proxy.py    # 通用代理（封面图等）
│   │   ├── textutil.py # 文本规范化（remarks 等）
│   │   └── security.py # SSRF 防护等
│   ├── data.db         # SQLite 数据库（运行时生成，勿提交）
│   ├── pyproject.toml  # uv 管理依赖
│   └── .venv/
├── deploy/
│   ├── nginx.conf.example   # Nginx 反代示例
│   └── tudounitv.service    # systemd 服务示例
├── manifest.json       # PWA 配置
└── service-worker.js   # PWA Service Worker
```

### 数据分层

| 层 | 内容 | 说明 |
|----|------|------|
| SQLite（持久） | 用户 / token / 观看历史 / 搜索历史 | 按用户隔离，服务端为准 |
| SQLite（镜像表） | `videos` 资源索引 | 定时轮转同步，首页/分类/搜索本地查库 |
| SQLite（热播条目） | `hot_rank_items` 热播榜条目并集 | 每次刷新成功并集合并（按 source+vod_id 去重），榜单拉取失败时兜底 |
| 内存 TTL 缓存 | 搜索 / 详情 / 热播榜结果 | 惰性过期 + LRU，降低对上游请求频率（热播榜 5 天） |
| localStorage | 前端本地缓存 | 仅当前账号的临时缓存，切换/登出即清空 |

## 快速开始

### 前置要求

- Python >= 3.13
- [uv](https://docs.astral.sh/uv/) 包管理器

### 开发模式（一键跑通）

```bash
cd backend
uv sync                                    # 安装依赖
uv run uvicorn app.main:app --host 127.0.0.1 --port 9797
```

`SERVE_STATIC=true`（默认）时，后端会挂载前端目录，访问 `http://127.0.0.1:9797` 即可看到完整站点。**首次访问会弹出注册框**，注册后自动登录。

服务启动时会自动建库（`backend/data.db`），若 `videos` 表为空则自动执行一次初始同步（拉取资源站列表，约 1-2 分钟）。

### 生产部署

前端由 Nginx 独立子域名托管，`/api/*` 反代到 Python 后端：

1. **后端**：用 systemd 管理进程（见 `deploy/tudounitv.service`）
   ```bash
   sudo cp deploy/tudounitv.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now tudounitv
   ```

2. **前端**：将仓库根目录配置到 Nginx（见 `deploy/nginx.conf.example`）
   ```nginx
   server {
       server_name tv.your-domain;
       root /var/www/libretv;
       location /api/ { proxy_pass http://127.0.0.1:9797; }
   }
   ```

3. 建议用 certbot 签发 HTTPS 证书。

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `PORT` | `9797` | 后端监听端口 |
| `HOST` | `127.0.0.1` | 后端监听地址 |
| `SERVE_STATIC` | `true` | 是否由后端挂载前端静态文件（生产设 `false`） |
| `LIBRETV_DB` | `backend/data.db` | SQLite 数据库文件路径 |
| `TOKEN_TTL_DAYS` | `7` | 登录 token 有效期（天） |
| `REQUEST_TIMEOUT` | `8` | 上游请求超时（秒） |
| `MAX_RETRIES` | `2` | 上游请求重试次数 |
| `CACHE_TTL` | `600` | 图片代理缓存有效期（秒） |
| `MAX_CACHE_BYTES` | `5242880` | 缓存体最大字节数（5MB） |
| `MAX_QUERY_LENGTH` | `100` | 搜索关键词最大长度 |
| `SYNC_INTERVAL_HOURS` | `24` | 资源镜像表同步间隔（小时） |
| `SYNC_PAGES_PER_SOURCE` | `10` | 每源每天拉取页数 |
| `SYNC_FRESH_PAGES` | `2` | 每次必拉的最新页数（保首页新鲜） |
| `SYNC_DEEP_CYCLE_DAYS` | `15` | 深页轮转周期（天），覆盖 `(总页数-新鲜页)×周期` 页深度 |
| `SYNC_STALE_DAYS` | `30` | 同步时清理 N 天未更新的残留行 |
| `TTL_CACHE_MAX_ITEMS` | `200` | 内存 TTL 缓存条数上限（LRU 淘汰） |
| `SEARCH_TTL` | `300` | 搜索结果缓存秒数 |
| `DETAIL_TTL` | `1800` | 详情缓存秒数 |

> 所有变量均有默认值，后端不读 `.env` 文件，通过环境变量传入（systemd `EnvironmentFile` 或命令行内联）。

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/health` | 健康检查（无需鉴权） |
| `POST` | `/api/auth/register` | 注册新用户（自动登录） |
| `POST` | `/api/auth/login` | 登录，返回 token |
| `POST` | `/api/auth/logout` | 登出（token 失效） |
| `GET` | `/api/me` | 当前用户信息 + 设置 |
| `PUT` | `/api/me/settings` | 保存用户设置（源勾选 / 偏好） |
| `GET/PUT/DELETE` | `/api/history` | 观看历史读写 / 清空 |
| `DELETE` | `/api/history/item` | 删除单条历史 |
| `GET/POST/DELETE` | `/api/search-history` | 搜索历史读写 / 清空 |
| `DELETE` | `/api/search-history/item` | 删除单条搜索历史 |
| `GET` | `/api/search` | 聚合搜索（`wd`，三级：缓存→镜像表→实时） |
| `GET` | `/api/vodlist` | 列表 / 分类（`source` / `cat` / `pg` + CMS 透传参数） |
| `GET` | `/api/items` | 首页聚合数据（查镜像表） |
| `GET` | `/api/iqiyi/hot` | 爱奇艺热播榜（榜单片名 → 聚合搜索映射，5 天缓存 + 后台预热 + 库兜底） |
| `GET` | `/api/hotrank/youku` | 优酷热播榜（解析榜单页内嵌数据 → 聚合搜索映射，同上） |
| `GET` | `/api/hotrank/tencent` | 腾讯热播榜（POST 频道接口 → 聚合搜索映射，同上） |
| `GET` | `/api/site-test` | 数据源可达性测试（`source`，设置面板用） |
| `GET` | `/api/proxy` | 通用代理（封面图等，`url` 参数） |
| `GET` | `/api/detail` | 视频详情 + 播放地址（`id` / `source`） |

除 `/api/health` 外，所有接口需携带 `Authorization: Bearer <token>` 请求头。

## 数据源

采用苹果 CMS V10 标准接口格式，已在后端 `backend/app/sites.py` 和前端 `js/config.js` / `js/customer_site.js` 各配置一份，两端需同步增删。

当前内置 10 个实测可用源：金鹰 / 光速 / U酷 / 百度 / 无尽 / 速博 / 魔都 / 最大 / 火狐 / 大地。第三方源随时可能失效，需定期复测（设置面板「测试所选源」可一键检测可达性，绿/红圆点展示）。

支持在设置面板添加自定义 CMS 源，格式：
- 接口地址：`https://example.com/api.php/provide/vod`
- 搜索：`?ac=videolist&wd=关键词`
- 详情：`?ac=detail&ids=视频ID`

## 键盘快捷键（播放页）

| 快捷键 | 功能 |
|--------|------|
| `空格` | 播放 / 暂停 |
| `←` / `→` | 快退 / 快进 5 秒 |
| `Alt + ←` / `Alt + →` | 上一集 / 下一集 |
| `↑` / `↓` | 音量增加 / 减小 |
| `F` | 切换全屏 |

## 开发约定：前端静态资源版本号

**只要改了 `js/` 或 `css/` 下的任何文件，必须同步 bump `index.html` 里对应资源的 `?v=N` 查询参数。** 这是硬性约定，否则线上会出现「HTML 是新的、JS 是旧的」的缓存错位问题。

```html
<script src="js/pages/discovery.js?v=13"></script>
<!-- 改了 js/pages/discovery.js 后必须改成 ↓ -->
<script src="js/pages/discovery.js?v=14"></script>
```

### 为什么必须这样做

- 生产环境前端由 Nginx + Cloudflare 托管，`?v=N` 是**完整的缓存键**：CF 边缘、浏览器 HTTP 缓存、PWA Service Worker 都以「URL 含 v 参数」为准。
- 版本号不变 → 所有缓存层都命中旧文件 → 客户端拿到的 JS 不包含本次改动。
- 后果是「新功能/修复在本地正常，线上无效果」，且难排查——页面结构（HTML）可能已更新，但行为逻辑（JS）还是旧的。

### 踩坑实例（2026-08）

首页新增「爱奇艺热播」板块：commit 里加了 `rowIqiyi` 的 HTML 结构（index.html）和 `renderIqiyiRow()` 渲染函数（discovery.js），但 **discovery.js 的 `?v=13` 没升到 14**。线上表现为：标题行「爱奇艺热播」正常显示（HTML 新），但行内永远空白（JS 旧，没有渲染函数）。修复只需 bump 版本号后重新发布。

### 操作清单

1. 改完 `js/`、`css/` 下的文件后，`git diff index.html` 确认对应 `?v=N` 已 +1
2. 验证：`curl -s https://你的域名/ | grep 'js/pages/discovery.js?v='` 确认线上已发布新版本号
3. 部署后建议强刷（Ctrl+Shift+R）一次，排除浏览器本地缓存

## 技术栈

- **后端**：Python 3.13 + FastAPI + httpx + uvicorn + sqlite3（零第三方存储依赖，uv 管理依赖）
- **前端**：HTML5 + CSS3 + JavaScript (ES6+)
- **Android TV 客户端**：Kotlin + Jetpack Compose + Media3 ExoPlayer + Retrofit
- **样式**：Tailwind CSS + 自定义设计系统
- **播放器**：ArtPlayer + HLS.js
- **存储**：SQLite（多用户数据 + 资源镜像表 + 热播条目并集）+ localStorage（前端缓存）+ 内存 TTL 缓存
- **PWA**：manifest.json + Service Worker

## 重要声明

- 本项目仅供学习和个人使用，请勿将部署的实例用于商业用途或公开服务
- 多用户注册采用明文传输的 token 鉴权，**生产环境务必配置 HTTPS**
- 如因公开分享导致的任何法律问题，用户需自行承担责任
- 项目开发者不对用户的使用行为承担任何法律责任

## 免责声明

TudouniTV 仅作为视频搜索工具，不存储、上传或分发任何视频内容。所有视频均来自第三方 API 接口提供的搜索结果。如有侵权内容，请联系相应的内容提供方。

本项目开发者不对使用本项目产生的任何后果负责。使用本项目时，您必须遵守当地的法律法规。

## 致谢

本项目基于以下开源项目：

- [MoonTV](https://github.com/senshinya/MoonTV)
- [OrionTV](https://github.com/zimplexing/OrionTV)
