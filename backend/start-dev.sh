#!/usr/bin/env bash
# 本地开发启动脚本（Git Bash / Linux / macOS 通用）。
#
# 口令从 backend/.env.local 读取，而不是写在命令行上 ——
# 命令行参数会出现在进程列表里，本机任何用户都能看到。
# 比起在脚本里硬编码密码，这样至少不会随脚本被提交到仓库。
set -euo pipefail
cd "$(dirname "$0")"

if [ -f .env.local ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env.local
  set +a
fi

: "${DB_PASSWORD:?请先在 backend/.env.local 中设置 DB_PASSWORD，可参考 .env.local.example}"

# 端口在这里显式定死，不吃外层环境里的 SERVER_PORT。
# 原因：某些终端（实测 WorkBuddy 内置终端）会向子进程注入 SERVER_PORT，值是它自己的端口，
# 而 `env` 里又看不到。一旦继承，应用会去抢那个端口并报「Port xxxxx was already in use」，
# 端口号看着毫无来由，极难排查。要换端口请用 SELFFACE_PORT，或直接传 --server.port=xxxx。
export SERVER_PORT="${SELFFACE_PORT:-8081}"

JAR=$(ls target/selfface-backend-*.jar 2>/dev/null | head -1 || true)
if [ -z "${JAR}" ]; then
  echo "没有找到构建产物，请先执行： mvn package -DskipTests" >&2
  exit 1
fi

echo "启动 ${JAR}（端口 ${SERVER_PORT}，数据库 ${DB_USERNAME:-root}@${DB_HOST:-localhost}:${DB_PORT:-3306}/${DB_NAME:-selfface}）"
exec java -jar "${JAR}" "$@"
