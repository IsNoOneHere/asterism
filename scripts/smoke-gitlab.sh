#!/bin/sh
set -eu

cd "$(dirname "$0")/.."

# Docker 模式沿用项目 .env；Apple Container 只继承当前 shell 的敏感变量。
if [ "${V5_CONTAINER_RUNTIME:-docker}" != "apple" ] && [ -f .env ]; then
  while IFS='=' read -r key value; do
    case "$key" in
      ""|\#*|*[!A-Za-z0-9_]*) continue ;;
    esac
    eval "already_set=\${$key+x}"
    [ -n "$already_set" ] || export "$key=$value"
  done < .env
fi

missing=""
[ -n "${ASTERISM_GITLAB_BASE_URL:-}" ] || missing="$missing ASTERISM_GITLAB_BASE_URL"
[ -n "${ASTERISM_GITLAB_TOKEN:-}" ] || missing="$missing ASTERISM_GITLAB_TOKEN"
[ -n "${V5_AGENT_API_KEY:-}" ] || missing="$missing V5_AGENT_API_KEY"
[ -n "${V5_MODEL_API_KEY:-}" ] || missing="$missing V5_MODEL_API_KEY"
[ -n "${V5_SMOKE_ADMIN_PASSWORD:-}" ] || missing="$missing V5_SMOKE_ADMIN_PASSWORD"
if [ -n "$missing" ]; then
  echo "SKIP: smoke-gitlab 缺少环境变量:$missing"
  exit 0
fi

sh ./scripts/doctor.sh
python3 ./scripts/smoke_gitlab.py
