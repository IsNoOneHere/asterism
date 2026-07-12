#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
DEST=${1:-"$(dirname "$ROOT")/agent-team"}

if [ -d "$DEST" ] && [ "$(find "$DEST" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  echo "导出目标必须为空: $DEST" >&2
  exit 2
fi

mkdir -p "$DEST"
rsync -a \
  --exclude '.git' --include '.env.example' --exclude '.env' --exclude '.env.*' \
  --exclude 'runtime' --exclude 'state' --exclude 'runs' --exclude 'logs' \
  --exclude '.venv' --exclude 'node_modules' --exclude 'target' --exclude 'dist' \
  --exclude '.pytest_cache' --exclude '__pycache__' --exclude '*.pyc' --exclude '*.tsbuildinfo' \
  "$ROOT/" "$DEST/"

if find "$DEST" -type f \( \( -name '.env*' ! -name '.env.example' \) -o -name '*.pyc' \) -print | grep -q . \
  || find "$DEST" -type d \( -name runtime -o -name state -o -name runs -o -name logs \
       -o -name target -o -name node_modules -o -name .venv -o -name dist \
       -o -name .pytest_cache -o -name __pycache__ \) -print | grep -q .; then
  echo "导出物仍包含运行时或密钥文件" >&2
  exit 3
fi

if command -v gitleaks >/dev/null 2>&1; then
  gitleaks detect --no-git --source "$DEST" --config "$DEST/.gitleaks.toml"
else
  echo "提示: 本机未安装 gitleaks，本次导出未执行密钥扫描；CI 会强制扫描。" >&2
fi

git -C "$DEST" init
git -C "$DEST" add .
git -C "$DEST" -c user.name=agent-team -c user.email=maintainers@example.invalid \
  -c commit.gpgsign=false commit -m "Initial open-source release"
echo "已导出独立仓库: $DEST"
