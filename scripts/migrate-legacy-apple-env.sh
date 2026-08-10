#!/bin/sh
set -eu

[ "$#" -eq 2 ] || { echo "用法: $0 <env-file> <release|build>" >&2; exit 2; }
ENV_FILE="$1"
IMAGE_SOURCE="$2"
STATE_DIR=$(dirname "$ENV_FILE")
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/asterism-legacy.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
chmod 700 "$TMP_DIR"

for name in control-plane agent-service worker; do
  container inspect "$name" > "$TMP_DIR/$name.json"
  chmod 600 "$TMP_DIR/$name.json"
done

env_value() {
  file="$1"
  key="$2"
  index=0
  while item=$(plutil -extract "0.configuration.initProcess.environment.$index" raw -o - "$file" 2>/dev/null); do
    case "$item" in
      "$key"=*) printf '%s' "${item#*=}"; return 0 ;;
    esac
    index=$((index + 1))
  done
  return 0
}

quote_value() {
  escaped=$(printf '%s' "$1" | sed "s/'/'\\\\''/g")
  printf "'%s'" "$escaped"
}

write_value() {
  printf '%s=%s\n' "$1" "$(quote_value "$2")"
}

cp_env="$TMP_DIR/control-plane.json"
agent_env="$TMP_DIR/agent-service.json"
worker_env="$TMP_DIR/worker.json"
db_password=$(env_value "$cp_env" V5_DB_PASSWORD)
callback_token=$(env_value "$cp_env" V5_WORKER_CALLBACK_TOKEN)
[ -n "$db_password" ] || { echo "旧容器缺少 V5_DB_PASSWORD，停止迁移" >&2; exit 2; }
[ -n "$callback_token" ] || { echo "旧容器缺少 V5_WORKER_CALLBACK_TOKEN，停止迁移" >&2; exit 2; }

repo_root=$(plutil -extract 0.configuration.mounts.0.source raw -o - "$worker_env" 2>/dev/null || true)
if [ -z "$repo_root" ] || [ ! -d "$repo_root" ]; then
  repo_root="$STATE_DIR/repos"
fi
umask 077
mkdir -p "$STATE_DIR/repos" "$STATE_DIR/backups"
{
  write_value ASTERISM_VERSION "0.1.5"
  write_value ASTERISM_IMAGE_SOURCE "$IMAGE_SOURCE"
  [ "$IMAGE_SOURCE" = build ] && write_value ASTERISM_PULL_POLICY never || write_value ASTERISM_PULL_POLICY missing
  write_value V5_CONTAINER_RUNTIME apple
  write_value V5_DB_USER "$(env_value "$cp_env" V5_DB_USER)"
  write_value V5_DB_PASSWORD "$db_password"
  write_value V5_WORKER_CALLBACK_TOKEN "$callback_token"
  # 旧库已存在管理员，不能把可能过期的初始密码再次当作当前凭据展示。
  write_value V5_ADMIN_INITIAL_PASSWORD ""
  write_value V5_REPO_ROOT "$repo_root"
  write_value V5_PUBLIC_URL "$(env_value "$worker_env" V5_PUBLIC_URL)"
  write_value V5_TEMPORAL_NAMESPACE "$(env_value "$worker_env" V5_TEMPORAL_NAMESPACE)"
  write_value V5_TEMPORAL_TASK_QUEUE "$(env_value "$worker_env" V5_TEMPORAL_TASK_QUEUE)"
  write_value V5_AGENT_MODEL "$(env_value "$agent_env" V5_AGENT_MODEL)"
  write_value V5_AGENT_BASE_URL "$(env_value "$agent_env" V5_AGENT_BASE_URL)"
  write_value V5_AGENT_API_KEY "$(env_value "$agent_env" V5_AGENT_API_KEY)"
  write_value V5_AGENT_MODEL_REQUEST_TIMEOUT_SECONDS "600"
  write_value V5_MODEL_PROVIDER "$(env_value "$worker_env" V5_MODEL_PROVIDER)"
  write_value V5_MODEL "$(env_value "$worker_env" V5_MODEL)"
  write_value V5_MODEL_BASE_URL "$(env_value "$worker_env" V5_MODEL_BASE_URL)"
  write_value V5_MODEL_API_KEY "$(env_value "$worker_env" V5_MODEL_API_KEY)"
  write_value V5_ENGINE_MAX_TURNS "$(env_value "$worker_env" V5_ENGINE_MAX_TURNS)"
  write_value V5_ENGINE_TIMEOUT_SECONDS "$(env_value "$worker_env" V5_ENGINE_TIMEOUT_SECONDS)"
  write_value V5_ENGINE_EFFORT_LEVEL "$(env_value "$worker_env" V5_ENGINE_EFFORT_LEVEL)"
  write_value V5_PRODUCT_AGENT_HTTP_TIMEOUT_SECONDS "660"
  write_value V5_RELEASE_PUSH "$(env_value "$worker_env" V5_RELEASE_PUSH)"
  write_value ASTERISM_GITLAB_BASE_URL "$(env_value "$cp_env" ASTERISM_GITLAB_BASE_URL)"
  write_value ASTERISM_GITLAB_TOKEN "$(env_value "$cp_env" ASTERISM_GITLAB_TOKEN)"
  write_value V5_APPLE_PROXY_URL "$(env_value "$agent_env" HTTP_PROXY)"
  write_value V5_OUTBOUND_PROXY_URL ""
  write_value V5_NO_PROXY "localhost,127.0.0.1,host.docker.internal,host.container.internal,postgres,temporal,server,runner"
} > "$ENV_FILE"
chmod 600 "$ENV_FILE"
echo "已继承旧 Apple Container 配置，密钥与现有数据库保持一致"
