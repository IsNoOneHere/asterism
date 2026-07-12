#!/bin/sh
set -eu

cd "$(dirname "$0")/.."

if [ "${CONFIRM:-}" != "yes" ]; then
  echo "拒绝执行：prod-reset 会删除 control_plane_v5 schema。请使用 CONFIRM=yes make prod-reset"
  exit 2
fi

docker compose --profile prod exec -T postgres psql -U "${V5_DB_USER:-agent_team}" -d agent_team_v5 <<'SQL'
drop schema if exists control_plane_v5 cascade;
create schema control_plane_v5;
SQL

echo "已重置 prod schema；重启 control-plane 后 Flyway 会重新迁移。"
