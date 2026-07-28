#!/bin/sh
set -eu

cd "$(dirname "$0")/.."

RUNTIME="${V5_CONTAINER_RUNTIME:-docker}"
APPLE_CONTAINER_BIN="${V5_APPLE_CONTAINER_BIN:-container}"
HELPER_IMAGE="docker.io/library/postgres:16"
BACKUP_DIR="${1:-${ASTERISM_HOME:-$(pwd)/.asterism}/backups/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$BACKUP_DIR"
BACKUP_DIR=$(CDPATH= cd -- "$BACKUP_DIR" && pwd)
mkdir -p "$BACKUP_DIR/temporal" "$BACKUP_DIR/attachments" "$BACKUP_DIR/artifacts"
chmod 700 "$BACKUP_DIR"

runtime_exec() {
  service="$1"; shift
  if [ "$RUNTIME" = apple ]; then "$APPLE_CONTAINER_BIN" exec "$service" "$@"; else compose exec -T "$service" "$@"; fi
}

compose() {
  if [ "${ASTERISM_IMAGE_SOURCE:-release}" = build ]; then
    docker compose -f docker-compose.yml -f docker-compose.build.yml "$@"
  else
    docker compose -f docker-compose.yml "$@"
  fi
}

restart_services() {
  if [ "$RUNTIME" = apple ]; then
    sh ./scripts/apple-container.sh up >/dev/null
  else
    compose up -d --no-build >/dev/null
  fi
}

copy_out() {
  service="$1"; source="$2"; target="$3"
  if [ "$RUNTIME" = apple ]; then
    case "$service" in
      temporal) volume=asterism-temporal-data ;;
      server) volume=asterism-control-plane-attachments ;;
      runner) volume=asterism-worker-artifacts ;;
    esac
    "$APPLE_CONTAINER_BIN" run --rm --volume "$volume:/source" --volume "$target:/target" \
      --entrypoint /bin/sh "$HELPER_IMAGE" -c 'cp -R /source/. /target/'
  else
    docker cp "$(compose ps --all -q "$service"):$source/." "$target"
  fi
}

printf '暂停无状态服务和 Temporal，创建一致的数据快照...\n'
trap restart_services EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if [ "$RUNTIME" = apple ]; then
  # Apple Container 的卷不能多挂载；删除可重建容器后再由 helper 读取持久卷。
  for service in server runner temporal; do
    "$APPLE_CONTAINER_BIN" stop "$service" >/dev/null 2>&1 || true
    "$APPLE_CONTAINER_BIN" delete "$service" >/dev/null 2>&1 || true
  done
else
  compose stop server runner temporal >/dev/null
fi

printf '备份 PostgreSQL...\n'
runtime_exec postgres pg_dump -U "${V5_DB_USER:-asterism}" -d asterism -Fc > "$BACKUP_DIR/postgres.dump"

copy_out temporal /home/temporal "$BACKUP_DIR/temporal"
copy_out server /app/runtime/attachments "$BACKUP_DIR/attachments"
copy_out runner /app/runtime/artifacts "$BACKUP_DIR/artifacts"

cat > "$BACKUP_DIR/manifest" <<EOF
version=${ASTERISM_VERSION:-0.1.5}
created_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
runtime=$RUNTIME
EOF
chmod 600 "$BACKUP_DIR/postgres.dump" "$BACKUP_DIR/manifest"

trap - EXIT INT TERM
restart_services
printf '备份完成: %s\n' "$BACKUP_DIR"
