# TudouniTV

> 自由观影，畅享精彩 — 基于苹果 CMS V10 资源站聚合的视频搜索与播放平台。

## 项目简介

TudouniTV 是一个前后端分离的视频聚合搜索工具。后端使用 **Python FastAPI** 聚合多个第三方资源站的搜索 / 详情 / 列表接口，前端为纯静态页面，通过浏览器直接播放 HLS 视频流。

- **视频流直连资源站**，不经服务器代理，带宽消耗极低
- 服务器仅代理元数据（搜索结果、详情、封面图）
- 支持多源聚合搜索、换源、进度记忆、自动连播
- 暗色 / 亮色双主题，响应式布局

## 架构

```
LibreTV/
├── index.html          # 首页（发现 / 分类 / 搜索）
├── player.html         # 播放页
├── watch.html          # 重定向跳转页
├── about.html          # 关于页
├── css/
│   ├── styles.css      # 全局基础样式
│   ├── design.css      # 设计系统 / 组件样式
│   ├── player.css      # 播放页专属样式
│   └── watch.css       # 重定向页专属样式
├── js/
│   ├── core/           # 公共基础模块（config / api / search / proxy-auth / password / ui …）
│   └── pages/          # 页面逻辑（app / index-page / player / player-page / discovery / douban …）
├── libs/               # 第三方库（hls.js / artplayer / tailwind / sha256）
├── backend/            # Python FastAPI 后端
│   ├── app/
│   │   ├── main.py     # 入口，注册路由 + 静态文件挂载
│   │   ├── config.py   # 环境变量配置
│   │   ├── sites.py    # 数据源定义（与前端 config.js / customer_site.js 同步）
│   │   ├── auth.py     # 密码登录 + token 校验
│   │   ├── search.py   # 聚合搜索
│   │   ├── detail.py   # 视频详情
│   │   ├── vodlist.py  # 列表 / 分类
│   │   ├── proxy.py    # 通用代理（封面图等）
│   │   └── home.py     # 首页数据聚合
│   ├── pyproject.toml  # uv 管理依赖
│   └── .venv/
├── deploy/
│   ├── nginx.conf.example   # Nginx 反代示例
│   └── tudounitv.service    # systemd 服务示例
├── manifest.json       # PWA 配置
└── service-worker.js   # PWA Service Worker
```

## 快速开始

### 前置要求

- Python >= 3.13
- [uv](https://docs.astral.sh/uv/) 包管理器

### 开发模式（一键跑通）

```bash
cd backend
uv sync                                    # 安装依赖
PASSWORD=your_password uv run uvicorn app.main:app --host 127.0.0.1 --port 9797
```

`SERVE_STATIC=true`（默认）时，后端会挂载前端目录，访问 `http://127.0.0.1:9797` 即可看到完整站点。

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
| `PASSWORD` | （无） | **必填**。登录密码，为空时全站不可用 |
| `PORT` | `9797` | 后端监听端口 |
| `HOST` | `127.0.0.1` | 后端监听地址 |
| `SERVE_STATIC` | `true` | 是否由后端挂载前端静态文件（开发模式） |
| `TOKEN_TTL_DAYS` | `7` | 登录 token 有效期（天） |
| `REQUEST_TIMEOUT` | `8` | 上游请求超时（秒） |
| `MAX_RETRIES` | `2` | 上游请求重试次数 |
| `CACHE_TTL` | `600` | 代理缓存有效期（秒） |
| `MAX_CACHE_BYTES` | `5242880` | 缓存体最大字节数（5MB） |

> 除 `PASSWORD` 外均有默认值。后端不读 `.env` 文件，通过环境变量传入（systemd `EnvironmentFile` 或命令行内联）。

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/health` | 健康检查 |
| `POST` | `/api/auth` | 密码登录，返回 token |
| `GET` | `/api/search` | 聚合搜索（`wd` 关键词） |
| `GET` | `/api/vodlist` | 列表 / 分类（`source` / `cat` / `pg`） |
| `GET` | `/api/items` | 首页聚合数据 |
| `GET` | `/api/proxy` | 通用代理（封面图等，`url` 参数） |
| `GET` | `/api/detail` | 视频详情 + 播放地址（`id` / `source`） |

除 `/api/health` 外，所有接口需携带 `Authorization: Bearer <token>` 请求头。

## 数据源

采用苹果 CMS V10 标准接口格式，已在后端 `backend/app/sites.py` 和前端 `js/config.js` / `js/customer_site.js` 各配置一份，两端需同步增删。

当前内置 10 个实测可用源：金鹰 / 光速 / U酷 / 百度 / 无尽 / 速博 / 魔都 / 最大 / 火狐 / 大地。第三方源随时可能失效，需定期复测。

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

## 技术栈

- **后端**：Python 3.13 + FastAPI + httpx + uvicorn（uv 管理依赖）
- **前端**：HTML5 + CSS3 + JavaScript (ES6+)
- **样式**：Tailwind CSS + 自定义设计系统
- **播放器**：ArtPlayer + HLS.js
- **存储**：localStorage（历史记录 / 评论 / 偏好设置）
- **PWA**：manifest.json + Service Worker

## 重要声明

- 本项目仅供学习和个人使用，部署时**必须**设置 `PASSWORD` 环境变量
- 请勿将部署的实例用于商业用途或公开服务
- 如因公开分享导致的任何法律问题，用户需自行承担责任
- 项目开发者不对用户的使用行为承担任何法律责任

## 免责声明

TudouniTV 仅作为视频搜索工具，不存储、上传或分发任何视频内容。所有视频均来自第三方 API 接口提供的搜索结果。如有侵权内容，请联系相应的内容提供方。

本项目开发者不对使用本项目产生的任何后果负责。使用本项目时，您必须遵守当地的法律法规。

## 致谢

本项目基于以下开源项目：

- [MoonTV](https://github.com/senshinya/MoonTV)
- [OrionTV](https://github.com/zimplexing/OrionTV)
