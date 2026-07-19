#!/bin/sh
set -eu

cd "$(dirname "$0")/.."

# Apple container 模式只继承当前 shell，避免从磁盘读取 key/token。
if [ "${V5_CONTAINER_RUNTIME:-docker}" != "apple" ] && [ -f .env ]; then
  while IFS='=' read -r key value; do
    case "$key" in
      ""|\#*) continue ;;
    esac
    case "$key" in
      *[!A-Za-z0-9_]* ) continue ;;
    esac
    eval "already_set=\${$key+x}"
    if [ -z "$already_set" ]; then
      export "$key=$value"
    fi
  done < .env
fi

if [ -z "${V5_AGENT_API_KEY:-}" ]; then
  echo "缺少 V5_AGENT_API_KEY，真实验收不能使用 fake 或空 key。"
  exit 2
fi

if [ -z "${V5_SMOKE_ADMIN_PASSWORD:-}" ]; then
  echo "缺少 V5_SMOKE_ADMIN_PASSWORD，请使用首次启动密码或显式配置的 admin 密码。"
  exit 2
fi

if [ -z "${V5_MODEL_API_KEY:-}" ]; then
  echo "缺少 V5_MODEL_API_KEY，Claude SDK Supervisor 无法执行。"
  exit 2
fi

sh ./scripts/doctor.sh
python3 ./scripts/smoke_real.py
