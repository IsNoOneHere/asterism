#!/bin/sh
set -eu

cd "$(dirname "$0")/.."

[ "$#" -eq 1 ] || { echo "用法: $0 <备份目录>" >&2; exit 2; }
BACKUP_DIR=$(CDPATH= cd -- "$1" 2>/dev/null && pwd) || { echo "备份目录不存在: $1" >&2; exit 2; }
[ -f "$BACKUP_DIR/postgres.dump" ] || { echo "缺少 postgres.dump" >&2; exit 2; }
for dir in temporal attachments artifacts; do
  [ -d "$BACKUP_DIR/$dir" ] || { echo "缺少 $dir 目录" >&2; exit 2; }
done

RUNTIME="${V5_CONTAINER_RUNTIME:-docker}"
APPLE_CONTAINER_BIN="${V5_APPLE_CONTAINER_BIN:-container}"
HELPER_IMAGE="docker.io/library/postgres:16"
TEMPORAL_IMAGE="docker.io/temporalio/temporal@sha256:906f9765cde508333ef191aab908bc724657b5f736cb5ead13921d9a45b33622"

runtime_exec() {
  service="$1"; shift
  # Apple Container 默认关闭标准输入，恢复 PostgreSQL 时必须显式保持输入流。
  if [ "$RUNTIME" = apple ]; then "$APPLE_CONTAINER_BIN" exec -i "$service" "$@"; else compose exec -T "$service" "$@"; fi
}

compose() {
  if [ "${ASTERISM_IMAGE_SOURCE:-release}" = build ]; then
    docker compose -f docker-compose.yml -f docker-compose.build.yml "$@"
  else
    docker compose -f docker-compose.yml "$@"
  fi
}

restore_volume() {
  volume="$1"; source="$2"
  if [ "$RUNTIME" = apple ]; then
    "$APPLE_CONTAINER_BIN" run --rm --user root --volume "$volume:/target" --volume "$source:/source" \
      --entrypoint /bin/sh "$HELPER_IMAGE" -c 'rm -rf /target/* /target/.[!.]* /target/..?* 2>/dev/null || true; cp -a /source/. /target/'
    if [ "$volume" = asterism-temporal-data ]; then
      # 主机备份恢复后重新交给 Temporal 用户，避免 SQLite 以只读方式启动。
      "$APPLE_CONTAINER_BIN" run --rm --user root --volume "$volume:/home/temporal" \
        --entrypoint /bin/sh "$TEMPORAL_IMAGE" -c 'chown -R temporal:temporal /home/temporal'
    fi
  else
    docker run --rm --volume "$volume:/target" --volume "$source:/source:ro" \
      --entrypoint /bin/sh "$HELPER_IMAGE" -c 'rm -rf /target/* /target/.[!.]* /target/..?* 2>/dev/null || true; cp -a /source/. /target/'
  fi
}

printf '停止应用服务并恢复备份...\n'
if [ "$RUNTIME" = apple ]; then
  for service in server runner temporal; do
    "$APPLE_CONTAINER_BIN" stop "$service" >/dev/null 2>&1 || true
    "$APPLE_CONTAINER_BIN" delete "$service" >/dev/null 2>&1 || true
  done
else
  compose stop server runner temporal >/dev/null
fi

runtime_exec postgres pg_restore -U "${V5_DB_USER:-asterism}" -d asterism --clean --if-exists --exit-on-error < "$BACKUP_DIR/postgres.dump"
if [ "$RUNTIME" = apple ]; then
  restore_volume asterism-temporal-data "$BACKUP_DIR/temporal"
  restore_volume asterism-control-plane-attachments "$BACKUP_DIR/attachments"
  restore_volume asterism-worker-artifacts "$BACKUP_DIR/artifacts"
  sh ./scripts/apple-container.sh up
else
  restore_volume asterism_temporal-data "$BACKUP_DIR/temporal"
  restore_volume asterism_control-plane-attachments "$BACKUP_DIR/attachments"
  restore_volume asterism_worker-artifacts "$BACKUP_DIR/artifacts"
  compose up -d --no-build
fi
sh ./scripts/doctor.sh basic
printf '恢复完成: %s\n' "$BACKUP_DIR"
