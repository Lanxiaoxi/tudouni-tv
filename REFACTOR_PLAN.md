# LibreTV 前后端分离重构方案（定稿）

> 状态：方案已与用户确认，待开发
> 日期：2026-08-07

## 0. 决策记录

| # | 决策点 | 结论 |
|---|--------|------|
| 1 | Python 框架 | **FastAPI**（异步代理/流式/超时原生支持，自带 /docs） |
| 2 | 聚合逻辑位置 | **后端聚合**（/api/search 一次返回全源聚合结果，前端变薄） |
| 3 | 鉴权方案 | **Token 登录态**（POST /api/auth 签发短期 token，替换现哈希方案，修复两个 P1 漏洞） |
| 4 | 部署形态 | **Nginx 子域名反代 + 代码两用**（生产：现有 Nginx 托管前端静态并反代 /api/* 到后端 127.0.0.1:8080；开发：后端可挂静态直接跑） |
| 5 | 用户环境 | Linux 服务器已有多服务、多子域名 Nginx 反代；前端用独立子域名访问 |

## 1. 保留不变的架构资产

- 前端 UI/交互/样式**全部不动**（HTML/CSS/js 17 模块页面逻辑原样保留）
- **视频流仍直连资源站**，不经后端（零带宽成本）
- **数据源配置仍在前端**（customer_site.js + 设置里自定义源 UI 不动），后端做"无状态通用转发 + 聚合"，不感知源列表

## 2. 目标架构

```
用户浏览器
  └─ tv.域名:443（现有 Nginx）
       ├─ /          → 前端静态文件（LibreTV 根目录）
       └─ /api/*     → 反代 127.0.0.1:8080
                          └─ Python 后端（FastAPI）
                               ├─ /api/auth     登录签发 token
                               ├─ /api/search   聚合搜索
                               ├─ /api/vodlist  发现流/分类列表
                               ├─ /api/proxy    通用元数据代理
                               └─ /api/health   健康检查
  （视频直连：浏览器 ── 虚线 ──> 第三方资源站）
```

## 3. 项目目录结构（前后端同仓库、分目录）

```
LibreTV/
├── index.html / player.html / about.html / watch.html   # 前端（原位不动）
├── js/ css/ libs/ image/                                # 前端（原位不动）
├── backend/                          # 新增：Python 后端
│   ├── app/
│   │   ├── __init__.py
│   │   ├── main.py                   # FastAPI 入口 + 静态挂载（开发两用）
│   │   ├── config.py                 # 配置（PASSWORD/端口/超时/缓存 TTL）
│   │   ├── auth.py                   # token 签发/校验/中间件
│   │   ├── security.py               # SSRF 防护（IP 解析校验）
│   │   ├── proxy.py                  # 通用代理（httpx 异步）
│   │   ├── search.py                 # 聚合搜索
│   │   └── vodlist.py                # 发现流/分类列表
│   ├── requirements.txt
│   └── run.sh
├── deploy/                           # 部署示例
│   ├── nginx.conf.example            # 子域名 server 块
│   └── tudounitv.service             # systemd 服务
├── REFACTOR_PLAN.md
├── server.mjs                        # 旧后端（对照用，上线后删除）
└── package.json                      # 旧依赖（前端不需要，可删）
```

## 4. API 契约

| 端点 | 方法 | 参数 | 说明 | 鉴权 |
|------|------|------|------|------|
| `/api/auth` | POST | `{password}` | 校验密码，返回 `{token, expires_in}` | 无 |
| `/api/search` | GET | `wd`(必填), `source`(可选，单源), `page` | 聚合搜索；缺 source 时并行查所有普通源并去重 | Bearer |
| `/api/vodlist` | GET | `cat`(movie/series/anime/variety，可选), `source`, `pg` | 发现流/分类页列表（聚合+前端 type_name 分类逻辑后移） | Bearer |
| `/api/proxy` | GET | `url`(必填，目标 URL) | 通用元数据代理（豆瓣等特殊源兜底） | Bearer |
| `/api/health` | GET | - | 健康检查 | 无 |

响应统一包裹 `{code, data, message}`；失败 `{code, message}`。
聚合端点对每个源 8s 超时，单源失败不影响整体（降级）。

## 5. 鉴权设计（修复现状两个 P1 漏洞）

现状问题：① sha256(密码) 注入公开 HTML → 凭证公开；② 时间戳参数可选 → 可绕过。

新方案：
- `POST /api/auth` 校验密码（环境变量 `PASSWORD`），成功签发 **32 字节随机 token**（服务端内存 dict，TTL 默认 7 天，重启失效——单用户场景可接受）
- 后续请求带 `Authorization: Bearer <token>`，中间件校验
- 密码明文仅存在服务器 `.env`，**不再注入任何页面源码**
- `/api/proxy` 同样走 Bearer 鉴权；SSRF 防护升级为"解析域名到 IP 后校验私网"（修复 IPv6 `[::1]` 绕过）
- 可选：全局限流中间件（slowapi，如 120 req/min/IP）

前端影响：`proxy-auth.js` 从"拼哈希 URL 参数"改为"token 存取 + Authorization 头"；`password.js` 从"本地 SHA-256 对比"改为"调 /api/auth"。

## 6. 前端改动清单（全部在请求层，UI 零改动）

| 文件 | 改动 |
|------|------|
| `js/config.js` | `PROXY_URL` → 删除，新增 `API_BASE=''`（同源相对路径）；删 `{{PASSWORD}}` 相关 |
| `js/proxy-auth.js` | 重写为 token 管理：login/logout/getToken/请求拦截器 |
| `js/api.js` | fetch 路径 `/proxy/...` → `/api/...`；鉴权参数 → `Authorization` 头 |
| `js/search.js` | 同 api.js；去掉前端多源并行逻辑（后端已聚合） |
| `js/discovery.js` | `fetchVodList`/`aggregateVodList` → 调 `/api/vodlist` |
| `js/douban.js` | 豆瓣请求 → `/api/proxy?url=` |
| `js/password.js` | 本地哈希验证 → 调 `/api/auth` 存 token |
| `index.html / player.html` | 删 `{{PASSWORD}}` 占位符与注入逻辑 |

## 7. 后端实现要点

- **httpx.AsyncClient** 异步转发，支持流式、超时（8s）、重试（2 次，仅 5xx/网络错误）、UA/Referer 反盗链
- **SSRF 防护**（security.py）：URL 解析 → `socket.getaddrinfo` 解析 hostname → 拒绝环回/私网/链路本地 IPv4+IPv6（含 `[::1]`、`::ffff:127.0.0.1` 映射）
- **内存缓存**：元数据代理结果按 URL+参数缓存（TTL 10min，dict + 过期清理），缓解资源站压力
- **日志**：logging 结构化输出（时间/IP/路径/状态/耗时）
- **静态挂载**：main.py 按 `SERVE_STATIC=true`（默认）挂载前端目录 → 开发一键跑；生产 Nginx 场景可关

## 8. 部署（示例）

nginx.conf.example 核心：
```nginx
server {
    server_name tv.你的域名;
    root /var/www/libretv;          # 前端目录
    location / { try_files $uri $uri/ /index.html; }
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 60s;
    }
}
```
systemd：`uvicorn app.main:app --host 127.0.0.1 --port 8080`，`Restart=always`。
环境变量：`PASSWORD`（必填）、`PORT`、`REQUEST_TIMEOUT`、`TOKEN_TTL_DAYS`、`SERVE_STATIC`。

## 9. 实施计划

1. **阶段一**：后端骨架（config/main/health/静态挂载）+ auth token
2. **阶段二**：security SSRF + proxy 通用代理 + 缓存
3. **阶段三**：search / vodlist 聚合端点
4. **阶段四**：前端适配（6 文件 + 2 HTML）
5. **阶段五**：本地联调（后端挂静态跑通全站搜索/播放/豆瓣）
6. **阶段六**：deploy/ 配置示例 + 后端文档，上线说明

## 10. 风险与对策

| 风险 | 对策 |
|------|------|
| 前端 fetch 路径遗漏（还有别处引用 /proxy/） | 改前全局 grep `PROXY_URL`、`/proxy/`、`window.__ENV__`，逐处处理 |
| 聚合逻辑迁移导致前端展示差异（去重/badge/分页） | 后端聚合保留前端现有字段（source_name/source_code），前端渲染代码零改动 |
| 自定义源（设置里 localStorage 的 customAPIs）兼容 | /api/search 支持 `source=custom&api_url=xxx` 透传 |
| token 内存态重启失效 | 单用户场景可接受；预留 JWT 升级路径 |
| 旧 server.mjs 残留 | 上线验证通过后删除，git 历史保留 |
