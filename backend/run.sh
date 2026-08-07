#!/usr/bin/env bash
# LibreTV 后端本地开发启动脚本（uv 管理依赖）
# 用法：./run.sh  或  SERVE_STATIC=false ./run.sh
set -e
cd "$(dirname "$0")"
exec uv run uvicorn app.main:app --host 127.0.0.1 --port "${PORT:-8080}" --reload
