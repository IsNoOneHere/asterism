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
runtime_exec postgres pg_isready -U "${V5_DB_USER:-agent_team}" >/dev/null 2>&1 && ok "DB 可连" || fail "DB 不可连"
# 生产 JDBC 固定使用 control_plane_v5 schema，doctor 也按真实 schema 检查。
checking "迁移表"
runtime_exec postgres psql -U "${V5_DB_USER:-agent_team}" -d agent_team_v5 -tAc "select count(*) from control_plane_v5.flyway_schema_history" >/dev/null 2>&1 && ok "迁移表可读" || fail "迁移表不可读"
checking "Temporal 容器"
runtime_running temporal && ok "Temporal 容器运行中" || fail "Temporal 未运行"
checking "control-plane 健康检查"
curl -fsS http://127.0.0.1:8085/healthz >/dev/null && ok "control-plane /healthz" || fail "control-plane /healthz 失败"
checking "agent-service 模型配置"
curl -fsS http://127.0.0.1:8090/healthz | grep -q '"model_config_available":true' && ok "agent-service model config 可用" || fail "agent-service model config 不可用"
checking "worker 容器"
runtime_running worker && ok "worker 容器运行中" || fail "worker 未运行"
checking "worker poller"
if runtime_exec temporal temporal task-queue describe \
  --address "$TEMPORAL_ADDRESS" \
  --namespace "${V5_TEMPORAL_NAMESPACE:-default}" \
  --task-queue "${V5_TEMPORAL_TASK_QUEUE:-agent-team-v5}" \
  --task-queue-type workflow \
  --output json >/tmp/agent-team-v5-task-queue.json 2>/tmp/agent-team-v5-task-queue.err \
  && grep -q '"identity"' /tmp/agent-team-v5-task-queue.json; then
  ok "worker poller 已注册"
elif runtime_exec temporal temporal task-queue describe \
  --address "$TEMPORAL_ADDRESS" \
  --namespace "${V5_TEMPORAL_NAMESPACE:-default}" \
  --task-queue "${V5_TEMPORAL_TASK_QUEUE:-agent-team-v5}" \
  --task-queue-type workflow >/tmp/agent-team-v5-task-queue.txt 2>/tmp/agent-team-v5-task-queue.err \
  && grep -q "Identity" /tmp/agent-team-v5-task-queue.txt; then
  ok "worker poller 已注册"
else
  fail "worker poller 未注册"
fi
