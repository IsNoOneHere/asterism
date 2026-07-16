#!/bin/sh
set -eu

cd "$(dirname "$0")/.."

CONTAINER_BIN="${V5_APPLE_CONTAINER_BIN:-container}"
NETWORK="asterism"
POSTGRES_VOLUME="asterism-postgres-data"
ATTACHMENTS_VOLUME="asterism-control-plane-attachments"
ARTIFACTS_VOLUME="asterism-worker-artifacts"
SERVICES="postgres temporal agent-service control-plane worker workbench"

fail() {
  printf '错误: %s\n' "$1" >&2
  exit 2
}

require_cli() {
  command -v "$CONTAINER_BIN" >/dev/null 2>&1 || fail "找不到 Apple container CLI: $CONTAINER_BIN"
  command -v python3 >/dev/null 2>&1 || fail "找不到 python3，无法解析 container inspect"
}

exists() {
  "$CONTAINER_BIN" inspect "$1" >/dev/null 2>&1
}

running() {
  "$CONTAINER_BIN" list --quiet | grep -Fx "$1" >/dev/null 2>&1
}

container_ip() {
  # Apple container 1.0 自定义网络无 DNS，按实机 inspect 结构读取动态 IPv4。
  "$CONTAINER_BIN" inspect "$1" | python3 -c '
import json, sys
data = json.load(sys.stdin)
if isinstance(data, list):
    data = data[0]
print(data["status"]["networks"][0]["ipv4Address"].split("/", 1)[0])
'
}

network_gateway() {
  "$CONTAINER_BIN" network inspect "$NETWORK" | python3 -c '
import json, sys
data = json.load(sys.stdin)
if isinstance(data, list):
    data = data[0]
print(data["status"]["ipv4Gateway"])
'
}

ensure_network() {
  "$CONTAINER_BIN" network inspect "$NETWORK" >/dev/null 2>&1 || "$CONTAINER_BIN" network create "$NETWORK"
}

ensure_volume() {
  "$CONTAINER_BIN" volume inspect "$1" >/dev/null 2>&1 || "$CONTAINER_BIN" volume create "$1"
}

container_proxy_url() {
  # 容器中的回环地址不是宿主机，Apple Container 通过内置域名访问宿主端口。
  printf '%s\n' "$1" | sed -E 's#^(https?://)(127\.0\.0\.1|localhost)(:[0-9]+)#\1host.container.internal\3#'
}

run_service() {
  name="$1"
  shift
  if exists "$name"; then
    if running "$name"; then
      printf '%s 已运行\n' "$name"
    else
      "$CONTAINER_BIN" start "$name"
    fi
    return
  fi
  "$CONTAINER_BIN" run --detach --name "$name" --network "$NETWORK" "$@"
}

run_external_service() {
  name="$1"
  shift
  if [ -n "${V5_APPLE_PROXY_URL:-}" ]; then
    run_service "$name" \
      --env HTTP_PROXY --env HTTPS_PROXY --env http_proxy --env https_proxy \
      --env NO_PROXY --env no_proxy "$@"
  else
    run_service "$name" "$@"
  fi
}

prepare_env() {
  [ -n "${V5_WORKER_CALLBACK_TOKEN:-}" ] || fail "缺少 V5_WORKER_CALLBACK_TOKEN"
  V5_REPO_ROOT="${V5_REPO_ROOT:-/tmp}"
  case "$V5_REPO_ROOT" in
    /*) ;;
    *) fail "V5_REPO_ROOT 必须是绝对路径" ;;
  esac
  [ -d "$V5_REPO_ROOT" ] || fail "V5_REPO_ROOT 不存在: $V5_REPO_ROOT"

  # 只导出到当前进程树，container run 按变量名继承，不把密钥写入参数或文件。
  V5_DB_USER="${V5_DB_USER:-asterism}"
  : "${V5_DB_PASSWORD:?必须设置 V5_DB_PASSWORD}"
  POSTGRES_DB="asterism"
  POSTGRES_USER="$V5_DB_USER"
  POSTGRES_PASSWORD="$V5_DB_PASSWORD"
  # Apple volume 根目录包含 lost+found，数据库数据放到独立子目录。
  PGDATA="/var/lib/postgresql/data/pgdata"
  V5_AGENT_MODEL="${V5_AGENT_MODEL:-gpt-4.1-mini}"
  V5_AGENT_BASE_URL="${V5_AGENT_BASE_URL:-}"
  V5_AGENT_WORKER_CALLBACK_TOKEN="${V5_AGENT_WORKER_CALLBACK_TOKEN:-$V5_WORKER_CALLBACK_TOKEN}"
  V5_EXECUTION_ENGINE="http"
  V5_PLANNER_PROVIDER="http"
  V5_MODEL_PROVIDER="${V5_MODEL_PROVIDER:-anthropic}"
  V5_MODEL_API_KEY="${V5_MODEL_API_KEY:-${V5_ANTHROPIC_API_KEY:-}}"
  V5_MODEL_BASE_URL="${V5_MODEL_BASE_URL:-${V5_ANTHROPIC_BASE_URL:-}}"
  V5_MODEL="${V5_MODEL:-${V5_ANTHROPIC_MODEL:-}}"
  V5_ENGINE_MAX_TURNS="${V5_ENGINE_MAX_TURNS:-${V5_CLAUDE_MAX_TURNS:-50}}"
  V5_ENGINE_TIMEOUT_SECONDS="${V5_ENGINE_TIMEOUT_SECONDS:-600}"
  V5_ENGINE_EFFORT_LEVEL="${V5_ENGINE_EFFORT_LEVEL:-${V5_CLAUDE_CODE_EFFORT_LEVEL:-}}"
  V5_ANTHROPIC_API_KEY="${V5_ANTHROPIC_API_KEY:-}"
  V5_ANTHROPIC_BASE_URL="${V5_ANTHROPIC_BASE_URL:-}"
  V5_ANTHROPIC_MODEL="${V5_ANTHROPIC_MODEL:-}"
  V5_CLAUDE_CODE_EFFORT_LEVEL="${V5_CLAUDE_CODE_EFFORT_LEVEL:-}"
  V5_CLAUDE_MAX_TURNS="${V5_CLAUDE_MAX_TURNS:-50}"
  V5_RELEASE_PUSH="${V5_RELEASE_PUSH:-false}"
  V5_PROFILE="prod"
  V5_TEMPORAL_NAMESPACE="${V5_TEMPORAL_NAMESPACE:-default}"
  V5_TEMPORAL_TASK_QUEUE="${V5_TEMPORAL_TASK_QUEUE:-asterism}"
  V5_ARTIFACTS_ROOT="/app/runtime/artifacts"
  V5_WORKSPACE_ROOT="/tmp/asterism-workspaces"
  SPRING_PROFILES_ACTIVE="temporal,llm"
  if [ -n "${V5_APPLE_PROXY_URL:-}" ]; then
    CONTAINER_PROXY_URL="$(container_proxy_url "$V5_APPLE_PROXY_URL")"
    HTTP_PROXY="$CONTAINER_PROXY_URL"
    HTTPS_PROXY="$CONTAINER_PROXY_URL"
    http_proxy="$CONTAINER_PROXY_URL"
    https_proxy="$CONTAINER_PROXY_URL"
    export HTTP_PROXY HTTPS_PROXY http_proxy https_proxy
  fi
  export V5_REPO_ROOT V5_DB_USER V5_DB_PASSWORD POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD PGDATA
  export V5_AGENT_MODEL V5_AGENT_BASE_URL V5_AGENT_API_KEY V5_AGENT_WORKER_CALLBACK_TOKEN
  export V5_WORKER_CALLBACK_TOKEN V5_EXECUTION_ENGINE V5_PLANNER_PROVIDER V5_ADMIN_INITIAL_PASSWORD
  export V5_MODEL_PROVIDER V5_MODEL_API_KEY V5_MODEL_BASE_URL V5_MODEL
  export V5_ENGINE_MAX_TURNS V5_ENGINE_TIMEOUT_SECONDS V5_ENGINE_EFFORT_LEVEL
  export V5_CLAUDE_MAX_TURNS V5_RELEASE_PUSH V5_PROFILE
  export V5_ANTHROPIC_API_KEY V5_ANTHROPIC_BASE_URL V5_ANTHROPIC_MODEL
  export V5_CLAUDE_CODE_EFFORT_LEVEL
  export V5_TEMPORAL_NAMESPACE V5_TEMPORAL_TASK_QUEUE
  export V5_ARTIFACTS_ROOT V5_WORKSPACE_ROOT SPRING_PROFILES_ACTIVE
}

build_image() {
  tag="$1"
  context="$2"
  if [ -n "${V5_APPLE_PROXY_URL:-}" ]; then
    proxy_url="$(container_proxy_url "$V5_APPLE_PROXY_URL")"
    "$CONTAINER_BIN" build \
      --build-arg "HTTP_PROXY=$proxy_url" \
      --build-arg "HTTPS_PROXY=$proxy_url" \
      --tag "$tag" "$context"
  else
    "$CONTAINER_BIN" build --tag "$tag" "$context"
  fi
}

build_images() {
  build_image asterism-agent-service:local ./agent-service
  build_image asterism-control-plane:local ./control-plane
  build_image asterism-worker:local ./worker
  build_image asterism-workbench:local ./workbench
}

wait_postgres() {
  count=0
  until "$CONTAINER_BIN" exec postgres pg_isready -U "$V5_DB_USER" >/dev/null 2>&1; do
    count=$((count + 1))
    [ "$count" -lt 60 ] || fail "PostgreSQL 60 秒内未就绪"
    sleep 1
  done
}

wait_http() {
  name="$1"
  url="$2"
  count=0
  until curl -fsS "$url" >/dev/null 2>&1; do
    count=$((count + 1))
    [ "$count" -lt 60 ] || fail "$name 60 秒内未就绪"
    sleep 1
  done
  printf '%s 已就绪\n' "$name"
}

wait_worker_poller() {
  count=0
  until "$CONTAINER_BIN" exec temporal temporal task-queue describe \
    --address 127.0.0.1:7233 \
    --namespace "${V5_TEMPORAL_NAMESPACE:-default}" \
    --task-queue "${V5_TEMPORAL_TASK_QUEUE:-asterism}" \
    --task-queue-type workflow \
    --output json 2>/dev/null | grep -q '"identity"'; do
    count=$((count + 1))
    [ "$count" -lt 60 ] || fail "worker poller 60 秒内未注册"
    sleep 1
  done
  printf 'worker poller 已注册\n'
}

up_services() {
  prepare_env
  ensure_network
  ensure_volume "$POSTGRES_VOLUME"
  ensure_volume "$ATTACHMENTS_VOLUME"
  ensure_volume "$ARTIFACTS_VOLUME"
  GATEWAY_IP="$(network_gateway)"

  run_service postgres \
    --env POSTGRES_DB --env POSTGRES_USER --env POSTGRES_PASSWORD --env PGDATA \
    --publish 55432:5432 \
    --volume "$POSTGRES_VOLUME:/var/lib/postgresql/data" \
    postgres:16
  wait_postgres
  POSTGRES_IP="$(container_ip postgres)"

  run_service temporal \
    --publish 7233:7233 --publish 8233:8233 \
    temporalio/temporal:latest \
    server start-dev --ip 0.0.0.0 --db-filename /tmp/temporal.db
  TEMPORAL_IP="$(container_ip temporal)"

  NO_PROXY="localhost,127.0.0.1,::1,$GATEWAY_IP,$POSTGRES_IP,$TEMPORAL_IP"
  no_proxy="$NO_PROXY"
  export NO_PROXY no_proxy
  run_external_service agent-service \
    --env V5_AGENT_MODEL --env V5_AGENT_BASE_URL --env V5_AGENT_API_KEY \
    --env V5_AGENT_WORKER_CALLBACK_TOKEN \
    --publish 8090:8090 \
    --entrypoint /bin/sh \
    asterism-agent-service:local \
    -c 'while [ ! -s /tmp/control-plane-url ]; do sleep 1; done; export V5_AGENT_CONTROL_PLANE_URL="$(cat /tmp/control-plane-url)"; if [ -s /tmp/no-proxy ]; then export NO_PROXY="$(cat /tmp/no-proxy)"; export no_proxy="$NO_PROXY"; fi; exec uvicorn agent_service.app:create_app --factory --host 0.0.0.0 --port 8090'
  V5_DB_URL="jdbc:postgresql://$POSTGRES_IP:5432/asterism?stringtype=unspecified&currentSchema=control_plane_v5,public"
  V5_TEMPORAL_TARGET="$TEMPORAL_IP:7233"
  V5_PRODUCT_AGENT_URL="http://$GATEWAY_IP:8090/prd-draft"
  V5_IMAGE_ANALYSIS_URL="http://$GATEWAY_IP:8090/analyze-image"
  V5_ATTACHMENT_ROOT="/app/runtime/attachments"
  export V5_DB_URL V5_TEMPORAL_TARGET V5_PRODUCT_AGENT_URL V5_IMAGE_ANALYSIS_URL V5_ATTACHMENT_ROOT

  run_service control-plane \
    --env SPRING_PROFILES_ACTIVE --env V5_DB_URL --env V5_DB_USER --env V5_DB_PASSWORD \
    --env V5_WORKER_CALLBACK_TOKEN --env V5_TEMPORAL_TARGET --env V5_TEMPORAL_NAMESPACE --env V5_TEMPORAL_TASK_QUEUE \
    --env V5_PRODUCT_AGENT_URL --env V5_IMAGE_ANALYSIS_URL \
    --env V5_ATTACHMENT_ROOT --env V5_PROFILE --env V5_ADMIN_INITIAL_PASSWORD \
    --publish 8085:8085 \
    --volume "$ATTACHMENTS_VOLUME:/app/runtime/attachments" \
    asterism-control-plane:local
  # HTTP 服务统一走宿主发布端口，避免 Apple Container 重启后动态 IP 变化。
  V5_AGENT_CONTROL_PLANE_URL="http://$GATEWAY_IP:8085"
  export V5_AGENT_CONTROL_PLANE_URL
  NO_PROXY="localhost,127.0.0.1,::1,$GATEWAY_IP,$POSTGRES_IP,$TEMPORAL_IP"
  no_proxy="$NO_PROXY"
  export NO_PROXY no_proxy
  if [ -n "${V5_APPLE_PROXY_URL:-}" ]; then
    "$CONTAINER_BIN" exec --env V5_AGENT_CONTROL_PLANE_URL --env NO_PROXY agent-service \
      /bin/sh -c 'printf "%s\n" "$NO_PROXY" > /tmp/no-proxy.tmp; mv /tmp/no-proxy.tmp /tmp/no-proxy; printf "%s\n" "$V5_AGENT_CONTROL_PLANE_URL" > /tmp/control-plane-url.tmp; mv /tmp/control-plane-url.tmp /tmp/control-plane-url'
  else
    "$CONTAINER_BIN" exec --env V5_AGENT_CONTROL_PLANE_URL agent-service \
      /bin/sh -c 'printf "%s\n" "$V5_AGENT_CONTROL_PLANE_URL" > /tmp/control-plane-url.tmp; mv /tmp/control-plane-url.tmp /tmp/control-plane-url'
  fi
  # 下游服务只在两个 HTTP 服务真正可用后启动。
  wait_http control-plane http://127.0.0.1:8085/healthz
  wait_http agent-service http://127.0.0.1:8090/healthz

  V5_CONTROL_PLANE_URL="http://$GATEWAY_IP:8085"
  V5_EXECUTION_HTTP_ENDPOINT="http://$GATEWAY_IP:8090/execute"
  V5_PLANNER_HTTP_ENDPOINT="http://$GATEWAY_IP:8090/plan"
  V5_AGENT_SERVICE_URL="http://$GATEWAY_IP:8090"
  export V5_CONTROL_PLANE_URL V5_EXECUTION_HTTP_ENDPOINT V5_PLANNER_HTTP_ENDPOINT V5_AGENT_SERVICE_URL

  run_external_service worker \
    --env V5_PROFILE --env V5_TEMPORAL_TARGET --env V5_TEMPORAL_NAMESPACE --env V5_TEMPORAL_TASK_QUEUE --env V5_CONTROL_PLANE_URL \
    --env V5_WORKER_CALLBACK_TOKEN --env V5_EXECUTION_ENGINE --env V5_EXECUTION_HTTP_ENDPOINT \
    --env V5_MODEL_PROVIDER --env V5_MODEL_API_KEY --env V5_MODEL_BASE_URL --env V5_MODEL \
    --env V5_ENGINE_MAX_TURNS --env V5_ENGINE_TIMEOUT_SECONDS --env V5_ENGINE_EFFORT_LEVEL \
    --env V5_ANTHROPIC_API_KEY --env V5_ANTHROPIC_BASE_URL --env V5_ANTHROPIC_MODEL \
    --env V5_CLAUDE_CODE_EFFORT_LEVEL --env V5_CLAUDE_MAX_TURNS --env V5_ARTIFACTS_ROOT \
    --env V5_PLANNER_PROVIDER --env V5_PLANNER_HTTP_ENDPOINT --env V5_AGENT_SERVICE_URL --env V5_RELEASE_PUSH --env V5_WORKSPACE_ROOT \
    --volume "$V5_REPO_ROOT:/repos" \
    --volume "$V5_REPO_ROOT:$V5_REPO_ROOT" \
    --volume "$ARTIFACTS_VOLUME:/app/runtime/artifacts" \
    asterism-worker:local
  wait_worker_poller

  V5_CONTROL_PLANE_HOST="$GATEWAY_IP"
  export V5_CONTROL_PLANE_HOST
  run_service workbench --env V5_CONTROL_PLANE_HOST --publish 8080:80 asterism-workbench:local
}

down_services() {
  # 单服务热更新可能创建旧 IP 兼容代理，全量关闭时一并清理。
  for name in control-plane-compat workbench worker control-plane agent-service temporal postgres; do
    if exists "$name"; then
      running "$name" && "$CONTAINER_BIN" stop "$name"
      "$CONTAINER_BIN" delete "$name"
    fi
  done
  "$CONTAINER_BIN" network inspect "$NETWORK" >/dev/null 2>&1 && "$CONTAINER_BIN" network delete "$NETWORK" || true
}

status_services() {
  for name in $SERVICES; do
    if running "$name"; then
      printf '%-16s running\n' "$name"
    elif exists "$name"; then
      printf '%-16s stopped\n' "$name"
    else
      printf '%-16s missing\n' "$name"
    fi
  done
}

service_name() {
  case "$1" in
    postgres|temporal|agent-service|control-plane|worker|workbench) printf '%s\n' "$1" ;;
    *) fail "未知服务: $1" ;;
  esac
}

logs_services() {
  if [ "$#" -gt 0 ]; then
    name="$(service_name "$1")"
    shift
    exists "$name" || fail "容器不存在: $name"
    "$CONTAINER_BIN" logs "$@" "$name"
    return
  fi
  for name in $SERVICES; do
    if exists "$name"; then
      printf '\n===== %s =====\n' "$name"
      "$CONTAINER_BIN" logs -n 100 "$name"
    fi
  done
}

usage() {
  echo "用法: $0 {build|up|down|restart|status|logs [service] [--follow]|doctor}"
}

require_cli
command="${1:-}"
case "$command" in
  build) build_images ;;
  up) up_services ;;
  down) down_services ;;
  restart) down_services; up_services ;;
  status) status_services ;;
  logs) shift; logs_services "$@" ;;
  doctor) V5_CONTAINER_RUNTIME=apple sh ./scripts/doctor.sh ;;
  *) usage; exit 2 ;;
esac
