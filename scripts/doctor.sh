#!/bin/sh
set -eu

cd "$(dirname "$0")/.."

ok() { printf "✓ %s\n" "$1"; }
fail() { printf "✗ %s\n" "$1"; exit 1; }
checking() { printf "检查 %s...\n" "$1"; }

RUNTIME="${V5_CONTAINER_RUNTIME:-docker}"
APPLE_CONTAINER_BIN="${V5_APPLE_CONTAINER_BIN:-container}"

runtime_exec() {
  service="$1"
  shift
  if [ "$RUNTIME" = "apple" ]; then
    "$APPLE_CONTAINER_BIN" exec "$service" "$@"
  else
    docker compose --profile prod exec -T "$service" "$@"
  fi
}

runtime_running() {
  service="$1"
  if [ "$RUNTIME" = "apple" ]; then
    "$APPLE_CONTAINER_BIN" list --quiet | grep -Fx "$service" >/dev/null 2>&1
  else
    docker compose --profile prod ps --status running -q "$service" | grep -q .
  fi
}

case "$RUNTIME" in
  docker)
    command -v docker >/dev/null 2>&1 || fail "找不到 docker"
    TEMPORAL_ADDRESS="temporal:7233"
    ;;
  apple)
    command -v "$APPLE_CONTAINER_BIN" >/dev/null 2>&1 || fail "找不到 Apple container CLI"
    TEMPORAL_ADDRESS="127.0.0.1:7233"
    ;;
  *) fail "不支持的 V5_CONTAINER_RUNTIME=$RUNTIME" ;;
esac

checking "DB 连接"
runtime_exec postgres pg_isready -U "${V5_DB_USER:-asterism}" >/dev/null 2>&1 && ok "DB 可连" || fail "DB 不可连"
# 生产 JDBC 固定使用 control_plane_v5 schema，doctor 也按真实 schema 检查。
checking "迁移表"
runtime_exec postgres psql -U "${V5_DB_USER:-asterism}" -d asterism -tAc "select count(*) from control_plane_v5.flyway_schema_history" >/dev/null 2>&1 && ok "迁移表可读" || fail "迁移表不可读"
checking "Temporal 容器"
runtime_running temporal && ok "Temporal 容器运行中" || fail "Temporal 未运行"
checking "control-plane 健康检查"
curl -fsS http://127.0.0.1:8085/healthz >/dev/null && ok "control-plane /healthz" || fail "control-plane /healthz 失败"
checking "agent-service 健康检查"
AGENT_HEALTH="$(curl -fsS http://127.0.0.1:8090/healthz)"
echo "$AGENT_HEALTH" | grep -q '"ok":true' && ok "agent-service /healthz" || fail "agent-service /healthz 失败"
checking "agent-service 模型配置"
# 系统 ModelProfile 是主配置源；仅在没有系统配置时检查旧环境回落。
MODEL_SYSTEM_ID="$(runtime_exec postgres psql -U "${V5_DB_USER:-asterism}" -d asterism -tAc \
  "select system_id from control_plane_v5.systems s where exists (select 1 from jsonb_array_elements(coalesce(model_provider_config->'modelProfiles','[]'::jsonb)) p where coalesce(p->>'model','')<>'' and coalesce(p->>'apiKey','')<>'') limit 1")"
if [ -n "$MODEL_SYSTEM_ID" ]; then
  curl -fsS "http://127.0.0.1:8090/readiness?system_id=$MODEL_SYSTEM_ID" | grep -q '"ready":true' \
    && ok "系统 ModelProfile 可用" || fail "系统 ModelProfile 不可用"
else
  echo "$AGENT_HEALTH" | grep -q '"model_config_available":true' \
    && ok "旧环境模型配置可用" || fail "未配置系统 ModelProfile 或旧环境模型"
fi
checking "worker 容器"
runtime_running worker && ok "worker 容器运行中" || fail "worker 未运行"
checking "worker Git"
runtime_exec worker git --version >/dev/null 2>&1 && ok "worker Git 可用" || fail "worker 未安装 Git"
checking "GitLab A→B 网络"
GITLAB_BASE_URL="$(runtime_exec control-plane /bin/sh -c 'printf "%s" "${ASTERISM_GITLAB_BASE_URL:-}"' 2>/dev/null || true)"
if [ -z "$GITLAB_BASE_URL" ]; then
  GITLAB_BASE_URL="$(runtime_exec postgres psql -U "${V5_DB_USER:-asterism}" -d asterism -tAc \
    "select gitlab_base_url from control_plane_v5.system_git_configs where release_mode='gitlab' and gitlab_base_url<>'' limit 1" 2>/dev/null || true)"
fi
if [ -z "$GITLAB_BASE_URL" ]; then
  ok "GitLab 未配置，local 模式跳过 A→B 检查"
elif runtime_exec worker python -c '
import sys, urllib.error, urllib.request
try:
    urllib.request.urlopen(sys.argv[1].rstrip("/") + "/api/v4/version", timeout=8).close()
except urllib.error.HTTPError as error:
    raise SystemExit(0 if error.code < 500 else 1)
except Exception:
    raise SystemExit(1)
' "$GITLAB_BASE_URL"; then
  ok "worker 可访问 GitLab API（仅需 A→B）"
else
  fail "worker 无法访问 GitLab API: $GITLAB_BASE_URL"
fi
checking "worker poller"
if runtime_exec temporal temporal task-queue describe \
  --address "$TEMPORAL_ADDRESS" \
  --namespace "${V5_TEMPORAL_NAMESPACE:-default}" \
  --task-queue "${V5_TEMPORAL_TASK_QUEUE:-asterism}" \
  --task-queue-type workflow \
  --output json >/tmp/asterism-task-queue.json 2>/tmp/asterism-task-queue.err \
  && grep -q '"identity"' /tmp/asterism-task-queue.json; then
  ok "worker poller 已注册"
elif runtime_exec temporal temporal task-queue describe \
  --address "$TEMPORAL_ADDRESS" \
  --namespace "${V5_TEMPORAL_NAMESPACE:-default}" \
  --task-queue "${V5_TEMPORAL_TASK_QUEUE:-asterism}" \
  --task-queue-type workflow >/tmp/asterism-task-queue.txt 2>/tmp/asterism-task-queue.err \
  && grep -q "Identity" /tmp/asterism-task-queue.txt; then
  ok "worker poller 已注册"
else
  fail "worker poller 未注册"
fi
