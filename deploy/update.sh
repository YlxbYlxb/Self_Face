#!/usr/bin/env bash
#
# 更新已部署的 SelfFace 到仓库最新版本 —— 在项目根目录执行：
#
#   sudo bash deploy/update.sh
#
# 会拉取最新代码、重新构建并滚动重启。数据库数据保留在数据卷里，不受影响。

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

step() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
info() { printf '\033[32m  ✓\033[0m %s\n' "$*"; }
warn() { printf '\033[33m  !\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31m[错误]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "需要 root 权限，请用：sudo bash deploy/update.sh"
cd "$PROJECT_DIR"
[ -f docker-compose.yml ] || die "请在项目根目录执行（当前：$PROJECT_DIR）"

# ---------- 拉取代码 ----------
step "拉取最新代码"
if [ -d .git ]; then
  BEFORE=$(git rev-parse --short HEAD)
  git pull --ff-only || die "git pull 失败。若本地有改动，先 git stash 或手动处理冲突"
  AFTER=$(git rev-parse --short HEAD)
  if [ "$BEFORE" = "$AFTER" ]; then
    info "已是最新（$AFTER）"
  else
    info "$BEFORE → $AFTER"
    git --no-pager log --oneline "$BEFORE..$AFTER" | sed 's/^/    /'
  fi
else
  warn "当前目录不是 git 仓库，跳过拉取。请自行覆盖项目文件后继续"
fi

# ---------- 检查 .env 是否缺新变量 ----------
# 新增的环境变量在老 .env 里不存在时，compose 会用到默认值或直接报错，
# 这里提前把差异指出来，比等服务起不来再排查省事。
if [ -f .env ] && [ -f .env.example ]; then
  MISSING=$(comm -23 \
    <(grep -oE '^[A-Z_]+=' .env.example | tr -d '=' | sort -u) \
    <(grep -oE '^[A-Z_]+=' .env | tr -d '=' | sort -u) || true)
  if [ -n "$MISSING" ]; then
    warn ".env 里缺少这些新变量（新功能可能需要）："
    echo "$MISSING" | sed 's/^/    /'
    warn "对照 .env.example 补上后重新执行本脚本"
  fi
fi

# ---------- 重新构建 ----------
step "重新构建并重启"
MEM_MB=$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)
if [ "$MEM_MB" -lt 4096 ]; then
  export COMPOSE_PARALLEL_LIMIT=1
  info "内存小于 4GB，串行构建"
fi

docker compose up -d --build

# ---------- 清理 ----------
step "清理悬空镜像"
# 每次构建都会产生一层旧镜像，不清理会慢慢把磁盘吃掉
docker image prune -f >/dev/null 2>&1 && info "已清理" || warn "清理跳过"

# ---------- 结果 ----------
step "当前状态"
docker compose ps

echo
warn "如果这次更新改了数据库表结构，auto-DDL 只建新表、不会给已有表加字段。"
warn "需要时手动执行：docker compose exec -T mysql mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" \$DB_NAME < 你的迁移.sql"
echo
info "更新完成"
