#!/bin/sh
set -eu

cd "$(dirname "$0")/.."

CONTAINER_BIN="${V5_APPLE_CONTAINER_BIN:-container}"
NETWORK="asterism"
POSTGRES_VOLUME="asterism-postgres-data"
TEMPORAL_VOLUME="asterism-temporal-data"
ATTACHMENTS_VOLUME="asterism-control-plane-attachments"
ARTIFACTS_VOLUME="asterism-worker-artifacts"
SERVICES="postgres temporal runner server"
POSTGRES_IMAGE="docker.io/library/postgres:16"
TEMPORAL_IMAGE="docker.io/temporalio/temporal@sha256:906f9765cde508333ef191aab908bc724657b5f736cb5ead13921d9a45b33622"
SERVER_RELEASE_IMAGE="ghcr.io/isnoonehere/asterism-server:${ASTERISM_VERSION:-0.1.5}"
RUNNER_RELEASE_IMAGE="ghcr.io/isnoonehere/asterism-runner:${ASTERISM_VERSION:-0.1.5}"

fail() { printf '错误: %s\n' "$1" >&2; exit 2; }

require_cli() {
  command -v "$CONTAINER_BIN" >/dev/null 2>&1 || fail "找不到 Apple Container CLI: $CONTAINER_BIN"
  command -v plutil >/dev/null 2>&1 || fail "找不到 macOS plutil"
  command -v curl >/dev/null 2>&1 || fail "找不到 curl"
  command -v tar >/dev/null 2>&1 || fail "找不到 tar"
}

ensure_system() {
  "$CONTAINER_BIN" system status 2>/dev/null | grep -q 'status.*running' || "$CONTAINER_BIN" system start
}

exists() { "$CONTAINER_BIN" inspect "$1" >/dev/null 2>&1; }
running() { "$CONTAINER_BIN" list --quiet | grep -Fx "$1" >/dev/null 2>&1; }

container_ip() {
  "$CONTAINER_BIN" inspect "$1" | plutil -extract 0.status.networks.0.ipv4Address raw -o - - | cut -d/ -f1
}

network_gateway() {
  "$CONTAINER_BIN" network inspect "$NETWORK" | plutil -extract 0.status.ipv4Gateway raw -o - -
}

ensure_network() {
  "$CONTAINER_BIN" network inspect "$NETWORK" >/dev/null 2>&1 || "$CONTAINER_BIN" network create "$NETWORK"
}

ensure_volume() {
  "$CONTAINER_BIN" volume inspect "$1" >/dev/null 2>&1 || "$CONTAINER_BIN" volume create "$1"
}

container_proxy_url() {
  printf '%s\n' "$1" | sed -E 's#^(https?://)(127\.0\.0\.1|localhost)(:[0-9]+)#\1host.container.internal\3#'
}

run_service() {
  name="$1"
  shift
  if exists "$name"; then
    if running "$name"; then
      return
    fi
    # Apple Container 偶尔无法重新 bootstrap stopped 容器；容器可重建，数据由独立卷保留。
    "$CONTAINER_BIN" delete "$name"
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

delete_container() {
  name="$1"
  if exists "$name"; then
    running "$name" && "$CONTAINER_BIN" stop "$name"
    "$CONTAINER_BIN" delete "$name"
  fi
}

prepare_env() {
  [ -n "${V5_WORKER_CALLBACK_TOKEN:-}" ] || fail "缺少 V5_WORKER_CALLBACK_TOKEN"
  : "${V5_DB_PASSWORD:?必须设置 V5_DB_PASSWORD}"
  V5_REPO_ROOT="${V5_REPO_ROOT:-$(pwd)/.asterism/repos}"
  [ -d "$V5_REPO_ROOT" ] || fail "V5_REPO_ROOT 不存在: $V5_REPO_ROOT"

  V5_DB_USER="${V5_DB_USER:-asterism}"
  POSTGRES_DB="asterism"
  POSTGRES_USER="$V5_DB_USER"
  POSTGRES_PASSWORD="$V5_DB_PASSWORD"
  PGDATA="/var/lib/postgresql/data/pgdata"
  V5_AGENT_MODEL="${V5_AGENT_MODEL:-gpt-4.1-mini}"
  V5_AGENT_BASE_URL="${V5_AGENT_BASE_URL:-}"
  V5_AGENT_API_KEY="${V5_AGENT_API_KEY:-}"
  V5_AGENT_WORKER_CALLBACK_TOKEN="$V5_WORKER_CALLBACK_TOKEN"
  V5_ADMIN_INITIAL_PASSWORD="${V5_ADMIN_INITIAL_PASSWORD:-}"
  V5_EXECUTION_ENGINE="claude_sdk_team"
  V5_MODEL_PROVIDER="${V5_MODEL_PROVIDER:-anthropic}"
  V5_MODEL_API_KEY="${V5_MODEL_API_KEY:-}"
  V5_MODEL_BASE_URL="${V5_MODEL_BASE_URL:-}"
  V5_MODEL="${V5_MODEL:-}"
  V5_ENGINE_MAX_TURNS="${V5_ENGINE_MAX_TURNS:-50}"
  V5_ENGINE_TIMEOUT_SECONDS="${V5_ENGINE_TIMEOUT_SECONDS:-600}"
  V5_ENGINE_EFFORT_LEVEL="${V5_ENGINE_EFFORT_LEVEL:-}"
  V5_RELEASE_PUSH="${V5_RELEASE_PUSH:-false}"
  V5_PUBLIC_URL="${V5_PUBLIC_URL:-http://127.0.0.1:8080}"
  ASTERISM_GITLAB_BASE_URL="${ASTERISM_GITLAB_BASE_URL:-}"
  ASTERISM_GITLAB_TOKEN="${ASTERISM_GITLAB_TOKEN:-}"
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
  export V5_WORKER_CALLBACK_TOKEN V5_EXECUTION_ENGINE V5_ADMIN_INITIAL_PASSWORD
  export V5_MODEL_PROVIDER V5_MODEL_API_KEY V5_MODEL_BASE_URL V5_MODEL
  export V5_ENGINE_MAX_TURNS V5_ENGINE_TIMEOUT_SECONDS V5_ENGINE_EFFORT_LEVEL
  export V5_RELEASE_PUSH V5_PUBLIC_URL V5_PROFILE
  export ASTERISM_GITLAB_BASE_URL ASTERISM_GITLAB_TOKEN
  export V5_TEMPORAL_NAMESPACE V5_TEMPORAL_TASK_QUEUE V5_ARTIFACTS_ROOT V5_WORKSPACE_ROOT SPRING_PROFILES_ACTIVE
}

build_image() {
  tag="$1"
  dockerfile="$2"
  if [ -n "${V5_APPLE_PROXY_URL:-}" ]; then
    proxy_url="$(container_proxy_url "$V5_APPLE_PROXY_URL")"
    "$CONTAINER_BIN" build -f "$dockerfile" \
      --build-arg "HTTP_PROXY=$proxy_url" --build-arg "HTTPS_PROXY=$proxy_url" \
      --tag "$tag" .
  else
    "$CONTAINER_BIN" build -f "$dockerfile" --tag "$tag" .
  fi
}

build_images() {
  # BuildKit 只保留为停止态构建缓存，不属于业务服务，后续升级可复用镜像层。
  if ! build_image asterism-runner:local worker/Dockerfile; then
    running buildkit && "$CONTAINER_BIN" stop buildkit || true
    return 1
  fi
  if ! build_image asterism-server:local control-plane/Dockerfile; then
    running buildkit && "$CONTAINER_BIN" stop buildkit || true
    return 1
  fi
  running buildkit && "$CONTAINER_BIN" stop buildkit || true
}

image_names() {
  if [ "${ASTERISM_IMAGE_SOURCE:-release}" = "build" ]; then
    RUNNER_IMAGE="asterism-runner:local"
    SERVER_IMAGE="asterism-server:local"
  else
    RUNNER_IMAGE="$RUNNER_RELEASE_IMAGE"
    SERVER_IMAGE="$SERVER_RELEASE_IMAGE"
  fi
}

pull_release_images() {
  [ "${ASTERISM_IMAGE_SOURCE:-release}" = "release" ] || return 0
  "$CONTAINER_BIN" image pull "$RUNNER_RELEASE_IMAGE"
  "$CONTAINER_BIN" image pull "$SERVER_RELEASE_IMAGE"
}

wait_postgres() {
  count=0
  until "$CONTAINER_BIN" exec postgres pg_isready -U "$V5_DB_USER" >/dev/null 2>&1; do
    count=$((count + 1))
    [ "$count" -lt 60 ] || fail "PostgreSQL 60 秒内未就绪"
    sleep 1
  done
}

wait_temporal() {
  count=0
  until "$CONTAINER_BIN" exec temporal temporal operator cluster health --address 127.0.0.1:7233 >/dev/null 2>&1; do
    count=$((count + 1))
    [ "$count" -lt 60 ] || fail "Temporal 60 秒内未就绪"
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

wait_runner() {
  count=0
  until "$CONTAINER_BIN" exec runner python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/healthz', timeout=3).close()" >/dev/null 2>&1; do
    count=$((count + 1))
    [ "$count" -lt 60 ] || fail "runner 60 秒内未就绪"
    sleep 1
  done
  printf 'runner 已就绪\n'
}

wait_worker_poller() {
  count=0
  until "$CONTAINER_BIN" exec temporal temporal task-queue describe \
    --address 127.0.0.1:7233 --namespace "$V5_TEMPORAL_NAMESPACE" \
    --task-queue "$V5_TEMPORAL_TASK_QUEUE" --task-queue-type workflow \
    --output json 2>/dev/null | grep -q '"identity"'; do
    count=$((count + 1))
    [ "$count" -lt 60 ] || fail "runner poller 60 秒内未注册"
    sleep 1
  done
  printf 'runner poller 已注册\n'
}

temporal_has_volume() {
  exists temporal && "$CONTAINER_BIN" inspect temporal | grep -q "$TEMPORAL_VOLUME"
}

migrate_legacy_temporal() {
  temporal_has_volume && return 0
  backup_dir="${ASTERISM_HOME:-$(pwd)/.asterism}/backups"
  archive="$backup_dir/legacy-temporal-pre-four.tgz"
  pending="$backup_dir/.legacy-temporal-migration-pending"
  umask 077
  mkdir -p "$backup_dir"
  chmod 700 "$backup_dir"

  if exists temporal; then
    printf '迁移旧 Temporal history 到持久卷...\n'
    temporary_archive="$archive.pending"
    # Apple Container 不能从停止容器复制文件；冻结 PID 1 后在线导出可得到一致的 SQLite 快照。
    trap '"$CONTAINER_BIN" exec temporal kill -CONT 1 >/dev/null 2>&1 || true; rm -f "$temporary_archive"' EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    running temporal || "$CONTAINER_BIN" start temporal
    "$CONTAINER_BIN" exec temporal kill -STOP 1
    "$CONTAINER_BIN" exec temporal /bin/sh -c 'cd /tmp && tar -czf - temporal.db*' > "$temporary_archive"
    "$CONTAINER_BIN" exec temporal kill -CONT 1
    chmod 600 "$temporary_archive"
    mv "$temporary_archive" "$archive"
    : > "$pending"
    trap - EXIT INT TERM
    delete_container temporal
  else
    [ -f "$archive" ] && [ -f "$pending" ] || return 0
    printf '继续上次中断的 Temporal history 迁移...\n'
  fi

  "$CONTAINER_BIN" run --rm --interactive --user root --name asterism-temporal-migrate \
    --volume "$TEMPORAL_VOLUME:/home/temporal" --entrypoint /bin/sh "$TEMPORAL_IMAGE" \
    -c 'tar -xzf - -C /home/temporal && chown -R temporal:temporal /home/temporal' < "$archive"
  rm -f "$pending"
  printf 'Temporal history 已迁移，迁移前快照保留在 %s\n' "$archive"
}

cleanup_legacy_apps() {
  for name in control-plane-compat workbench worker control-plane agent-service; do
    delete_container "$name"
  done
}

up_services() {
  prepare_env
  ensure_network
  ensure_volume "$POSTGRES_VOLUME"
  ensure_volume "$TEMPORAL_VOLUME"
  ensure_volume "$ATTACHMENTS_VOLUME"
  ensure_volume "$ARTIFACTS_VOLUME"
  image_names
  cleanup_legacy_apps
  migrate_legacy_temporal

  run_service postgres \
    --env POSTGRES_DB --env POSTGRES_USER --env POSTGRES_PASSWORD --env PGDATA \
    --publish 127.0.0.1:55432:5432 \
    --volume "$POSTGRES_VOLUME:/var/lib/postgresql/data" "$POSTGRES_IMAGE"
  wait_postgres
  run_service temporal \
    --publish 127.0.0.1:7233:7233 --publish 127.0.0.1:8233:8233 \
    --volume "$TEMPORAL_VOLUME:/home/temporal" "$TEMPORAL_IMAGE" \
    server start-dev --ip 0.0.0.0 --db-filename /home/temporal/temporal.db
  wait_temporal

  POSTGRES_IP="$(container_ip postgres)"
  TEMPORAL_IP="$(container_ip temporal)"
  GATEWAY_IP="$(network_gateway)"
  GITLAB_HOST="$(printf '%s\n' "$ASTERISM_GITLAB_BASE_URL" | sed -E 's#^https?://##; s#[:/].*$##')"
  NO_PROXY="localhost,127.0.0.1,::1,$GATEWAY_IP,$POSTGRES_IP,$TEMPORAL_IP${GITLAB_HOST:+,$GITLAB_HOST}"
  no_proxy="$NO_PROXY"
  V5_TEMPORAL_TARGET="$TEMPORAL_IP:7233"
  V5_CONTROL_PLANE_URL="http://$GATEWAY_IP:8080"
  V5_AGENT_CONTROL_PLANE_URL="$V5_CONTROL_PLANE_URL"
  V5_AGENT_SERVICE_URL="http://127.0.0.1:8090"
  export NO_PROXY no_proxy V5_TEMPORAL_TARGET V5_CONTROL_PLANE_URL V5_AGENT_CONTROL_PLANE_URL V5_AGENT_SERVICE_URL

  delete_container runner
  run_external_service runner \
    --env V5_PROFILE --env V5_TEMPORAL_TARGET --env V5_TEMPORAL_NAMESPACE --env V5_TEMPORAL_TASK_QUEUE \
    --env V5_CONTROL_PLANE_URL --env V5_WORKER_CALLBACK_TOKEN --env V5_EXECUTION_ENGINE \
    --env V5_MODEL_PROVIDER --env V5_MODEL_API_KEY --env V5_MODEL_BASE_URL --env V5_MODEL \
    --env V5_ENGINE_MAX_TURNS --env V5_ENGINE_TIMEOUT_SECONDS --env V5_ENGINE_EFFORT_LEVEL \
    --env V5_ARTIFACTS_ROOT --env V5_AGENT_SERVICE_URL --env V5_RELEASE_PUSH --env V5_WORKSPACE_ROOT --env V5_PUBLIC_URL \
    --env V5_AGENT_MODEL --env V5_AGENT_BASE_URL --env V5_AGENT_API_KEY \
    --env V5_AGENT_CONTROL_PLANE_URL --env V5_AGENT_WORKER_CALLBACK_TOKEN \
    --volume "$V5_REPO_ROOT:/repos" --volume "$V5_REPO_ROOT:$V5_REPO_ROOT" \
    --volume "$ARTIFACTS_VOLUME:/app/runtime/artifacts" "$RUNNER_IMAGE"
  wait_runner

  RUNNER_IP="$(container_ip runner)"
  V5_DB_URL="jdbc:postgresql://$POSTGRES_IP:5432/asterism?stringtype=unspecified&currentSchema=control_plane_v5,public"
  V5_PRODUCT_AGENT_URL="http://$RUNNER_IP:8090/prd-draft"
  V5_PRODUCT_AGENT_MEMORY_URL="http://$RUNNER_IP:8090/prd-memory-candidates"
  V5_IMAGE_ANALYSIS_URL="http://$RUNNER_IP:8090/analyze-image"
  V5_ATTACHMENT_ROOT="/app/runtime/attachments"
  export V5_DB_URL V5_PRODUCT_AGENT_URL V5_PRODUCT_AGENT_MEMORY_URL V5_IMAGE_ANALYSIS_URL V5_ATTACHMENT_ROOT

  delete_container server
  run_service server \
    --env SPRING_PROFILES_ACTIVE --env V5_DB_URL --env V5_DB_USER --env V5_DB_PASSWORD \
    --env V5_WORKER_CALLBACK_TOKEN --env V5_TEMPORAL_TARGET --env V5_TEMPORAL_NAMESPACE --env V5_TEMPORAL_TASK_QUEUE \
    --env V5_PRODUCT_AGENT_URL --env V5_PRODUCT_AGENT_MEMORY_URL --env V5_IMAGE_ANALYSIS_URL --env V5_ATTACHMENT_ROOT \
    --env V5_PROFILE --env V5_ADMIN_INITIAL_PASSWORD --env ASTERISM_GITLAB_BASE_URL --env ASTERISM_GITLAB_TOKEN \
    --publish 8080:8085 --volume "$ATTACHMENTS_VOLUME:/app/runtime/attachments" "$SERVER_IMAGE"
  wait_http server http://127.0.0.1:8080/healthz
  wait_worker_poller
}

stop_services() {
  for name in server runner temporal postgres; do
    running "$name" && "$CONTAINER_BIN" stop "$name" || true
  done
}

upgrade_services() {
  prepare_env
  if [ "${ASTERISM_IMAGE_SOURCE:-release}" = "build" ]; then build_images; else pull_release_images; fi
  up_services
  printf 'server 与 runner 已升级，状态卷保持不变\n'
}

status_services() {
  for name in $SERVICES; do
    if running "$name"; then printf '%-12s running\n' "$name"
    elif exists "$name"; then printf '%-12s stopped\n' "$name"
    else printf '%-12s missing\n' "$name"
    fi
  done
}

service_name() {
  case "$1" in postgres|temporal|runner|server) printf '%s\n' "$1" ;; *) fail "未知服务: $1" ;; esac
}

logs_services() {
  if [ "$#" -gt 0 ]; then
    name="$(service_name "$1")"
    shift
    "$CONTAINER_BIN" logs "$@" "$name"
  else
    for name in $SERVICES; do
      exists "$name" && { printf '\n===== %s =====\n' "$name"; "$CONTAINER_BIN" logs -n 100 "$name"; }
    done
  fi
}

require_cli
ensure_system
command="${1:-}"
case "$command" in
  build) prepare_env; build_images ;;
  up) up_services ;;
  upgrade) upgrade_services ;;
  stop) stop_services ;;
  status) status_services ;;
  logs) shift; logs_services "$@" ;;
  doctor) V5_CONTAINER_RUNTIME=apple sh ./scripts/doctor.sh "${2:-full}" ;;
  *) echo "用法: $0 {build|up|upgrade|stop|status|logs [service]|doctor [basic|full]}"; exit 2 ;;
esac
