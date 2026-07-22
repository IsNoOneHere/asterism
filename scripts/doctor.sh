#!/bin/sh
set -eu

cd "$(dirname "$0")/.."

ok() { printf '✓ %s\n' "$1"; }
warn() { printf '! %s\n' "$1"; }
fail() { printf '✗ %s\n' "$1"; exit 1; }
checking() { printf '检查 %s...\n' "$1"; }

MODE="${1:-full}"
RUNTIME="${V5_CONTAINER_RUNTIME:-docker}"
APPLE_CONTAINER_BIN="${V5_APPLE_CONTAINER_BIN:-container}"

runtime_exec() {
  service="$1"
  shift
  if [ "$RUNTIME" = apple ]; then
    "$APPLE_CONTAINER_BIN" exec "$service" "$@"
  else
    docker compose exec -T "$service" "$@"
  fi
}

runtime_running() {
  service="$1"
  if [ "$RUNTIME" = apple ]; then
    "$APPLE_CONTAINER_BIN" list --quiet | grep -Fx "$service" >/dev/null 2>&1
  else
    docker compose ps --status running -q "$service" | grep -q .
  fi
}

case "$RUNTIME" in
  docker) command -v docker >/dev/null 2>&1 || fail "找不到 Docker" ;;
  apple) command -v "$APPLE_CONTAINER_BIN" >/dev/null 2>&1 || fail "找不到 Apple Container" ;;
  *) fail "不支持的运行时: $RUNTIME" ;;
esac
case "$MODE" in basic|full) ;; *) fail "doctor 只支持 basic 或 full" ;; esac

for service in postgres temporal runner server; do
  checking "$service 容器"
  runtime_running "$service" && ok "$service 运行中" || fail "$service 未运行"
done

checking "PostgreSQL 与迁移表"
runtime_exec postgres pg_isready -U "${V5_DB_USER:-asterism}" >/dev/null 2>&1 || fail "PostgreSQL 不可连"
runtime_exec postgres psql -U "${V5_DB_USER:-asterism}" -d asterism -tAc \
  "select count(*) from control_plane_v5.flyway_schema_history" >/dev/null 2>&1 || fail "Flyway 迁移表不可读"
ok "PostgreSQL 与 Flyway 正常"

checking "Temporal"
runtime_exec temporal temporal operator cluster health --address 127.0.0.1:7233 >/dev/null 2>&1 \
  && ok "Temporal 正常" || fail "Temporal 不可用"

checking "Runner HTTP 与 Git"
runtime_exec runner python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/healthz', timeout=5).close()" >/dev/null 2>&1 \
  || fail "Runner HTTP 不可用"
runtime_exec runner git --version >/dev/null 2>&1 || fail "Runner 未安装 Git"
ok "Runner HTTP 与 Git 正常"

checking "Runner poller"
runtime_exec temporal temporal task-queue describe \
  --address 127.0.0.1:7233 --namespace "${V5_TEMPORAL_NAMESPACE:-default}" \
  --task-queue "${V5_TEMPORAL_TASK_QUEUE:-asterism}" --task-queue-type workflow \
  --output json 2>/tmp/asterism-task-queue.err | grep -q '"identity"' \
  && ok "Runner poller 已注册" || fail "Runner poller 未注册"

checking "Server 与 Workbench"
curl -fsS http://127.0.0.1:8080/healthz >/dev/null || fail "Server /healthz 失败"
curl -fsS http://127.0.0.1:8080/ | grep -q '<title>Asterism</title>' || fail "Workbench 页面失败"
ok "Server API 与 Workbench 正常"

[ "$MODE" = full ] || { ok "基础安装检查完成"; exit 0; }

checking "模型配置"
MODEL_SYSTEM_ID="$(runtime_exec postgres psql -U "${V5_DB_USER:-asterism}" -d asterism -tAc \
  "select system_id from control_plane_v5.systems s where exists (select 1 from jsonb_array_elements(coalesce(model_provider_config->'modelProfiles','[]'::jsonb)) p where coalesce(p->>'model','')<>'' and coalesce(p->>'apiKey','')<>'') limit 1")"
if [ -z "$MODEL_SYSTEM_ID" ]; then
  warn "尚未配置 Model Profile；基础服务可用，进入页面后再配置模型"
else
  runtime_exec runner python -c '
import json, os, sys, urllib.request
url = "http://127.0.0.1:8090/readiness?system_id=" + sys.argv[1]
request = urllib.request.Request(url, headers={"Authorization": "Bearer " + os.environ["V5_WORKER_CALLBACK_TOKEN"]})
with urllib.request.urlopen(request, timeout=8) as response:
    raise SystemExit(0 if json.load(response).get("ready") else 1)
' "$MODEL_SYSTEM_ID" && ok "Model Profile 可用" || fail "Model Profile 不可用"
fi

checking "GitLab A→B 网络"
GITLAB_BASE_URL="$(runtime_exec server /bin/sh -c 'printf "%s" "${ASTERISM_GITLAB_BASE_URL:-}"' 2>/dev/null || true)"
if [ -z "$GITLAB_BASE_URL" ]; then
  GITLAB_BASE_URL="$(runtime_exec postgres psql -U "${V5_DB_USER:-asterism}" -d asterism -tAc \
    "select gitlab_base_url from control_plane_v5.system_git_configs where release_mode='gitlab' and gitlab_base_url<>'' limit 1" 2>/dev/null || true)"
fi
if [ -z "$GITLAB_BASE_URL" ]; then
  ok "GitLab 未配置，local 模式跳过"
elif runtime_exec runner python -c '
import sys, urllib.error, urllib.request
try:
    urllib.request.urlopen(sys.argv[1].rstrip("/") + "/api/v4/version", timeout=8).close()
except urllib.error.HTTPError as error:
    raise SystemExit(0 if error.code < 500 else 1)
except Exception:
    raise SystemExit(1)
' "$GITLAB_BASE_URL"; then
  ok "Runner 可访问 GitLab API"
else
  fail "Runner 无法访问 GitLab API: $GITLAB_BASE_URL"
fi

ok "完整业务检查完成"
