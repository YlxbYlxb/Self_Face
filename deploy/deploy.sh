#!/usr/bin/env bash
#
# SelfFace 一键部署脚本 —— 在云服务器上、项目根目录执行：
#
#   sudo bash deploy/deploy.sh
#
# 想换端口：
#   sudo WEB_PORT=8080 bash deploy/deploy.sh
#
# 幂等：可以反复执行。
#   · 已装好 Docker 不会重装
#   · 已存在的 .env 不会覆盖（换掉密钥会让已保存的 LLM API Key 解不开）
#   · 数据卷不会被清空，题库和用户数据都保留

set -euo pipefail

WEB_PORT="${WEB_PORT:-8090}"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---------- 输出 ----------
step() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
info() { printf '\033[32m  ✓\033[0m %s\n' "$*"; }
warn() { printf '\033[33m  !\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31m[错误]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------- 前置检查 ----------
[ "$(id -u)" -eq 0 ] || die "需要 root 权限，请用：sudo bash deploy/deploy.sh"

cd "$PROJECT_DIR"
[ -f docker-compose.yml ] || die "没找到 docker-compose.yml，请在项目根目录执行（当前：$PROJECT_DIR）"
[ -f .env.example ] || die "没找到 .env.example，项目文件不完整"

step "环境检查"
if [ -f /etc/os-release ]; then
  # shellcheck disable=SC1091
  . /etc/os-release && info "系统：${PRETTY_NAME:-unknown}"
fi
info "项目目录：$PROJECT_DIR"

# ---------- 内存 / swap ----------
# 学生机常见 2核2G，而 Maven 编译 + Vite 打包同时跑很容易把内存吃满，
# 构建中途被 OOM Killer 杀掉的表现是「莫名其妙构建失败」，很难定位。
MEM_MB=$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)
SWAP_MB=$(awk '/SwapTotal/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)

if [ "$MEM_MB" -lt 3500 ] && [ "$SWAP_MB" -lt 512 ]; then
  step "内存只有 ${MEM_MB}MB 且没开 swap，补 2GB 交换空间"
  if [ -f /swapfile ]; then
    warn "/swapfile 已存在但未启用，尝试启用"
    swapon /swapfile 2>/dev/null || true
  else
    fallocate -l 2G /swapfile 2>/dev/null \
      || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
    swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
    info "已创建并启用 2GB swap（写入 /etc/fstab，重启后仍生效）"
  fi
fi

# ---------- Docker ----------
step "检查 Docker"
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  info "已安装：$(docker --version)"
else
  info "未安装，开始安装"
  if ! command -v curl >/dev/null 2>&1; then
    if command -v apt-get >/dev/null 2>&1; then
      apt-get update -qq && apt-get install -y -qq curl
    elif command -v dnf >/dev/null 2>&1; then
      dnf install -y -q curl
    elif command -v yum >/dev/null 2>&1; then
      yum install -y -q curl
    fi
  fi
  curl -fsSL --connect-timeout 30 --max-time 300 https://get.docker.com -o /tmp/get-docker.sh \
    || die "下载 Docker 安装脚本失败。检查服务器能否访问外网，或改用云厂商镜像源手动安装后重跑本脚本"
  sh /tmp/get-docker.sh || die "Docker 安装失败"
  rm -f /tmp/get-docker.sh
  systemctl enable --now docker
  info "安装完成：$(docker --version)"
fi

# 云服务器上 SELinux 会让挂载的目录在容器里不可写，报错信息很隐晦
if command -v getenforce >/dev/null 2>&1 && [ "$(getenforce 2>/dev/null)" = "Enforcing" ]; then
  warn "SELinux 处于 Enforcing。若容器启动后报权限错误，执行 setenforce 0 后重跑本脚本"
fi

# ---------- 镜像加速 ----------
step "配置镜像加速与容器日志轮转"
if [ -f /etc/docker/daemon.json ]; then
  warn "/etc/docker/daemon.json 已存在，跳过（不覆盖你已有的配置）"
  warn "如果拉镜像很慢，手动把 registry-mirrors 加进这个文件再 systemctl restart docker"
else
  mkdir -p /etc/docker
  cat > /etc/docker/daemon.json <<'JSON'
{
  "registry-mirrors": [
    "https://docker.xuanyuan.me",
    "https://docker.1ms.run",
    "https://docker.m.daocloud.io"
  ],
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "50m",
    "max-file": "3"
  }
}
JSON
  systemctl restart docker
  info "已配置 3 个镜像源（末尾不带 /，带了会导致 TLS 握手失败）"
  info "已限制容器日志单个 50MB × 3 份，避免长期运行把磁盘写满"
fi

# ---------- .env ----------
step "准备 .env"

# 纯 hex 的随机值：不含 / + = 等字符，写进 .env 或当 MySQL 口令都不需要转义
gen_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

# 有就改、没有就追加，避免依赖 .env.example 里的确切写法
set_env() {
  local key="$1" val="$2"
  if grep -qE "^${key}=" .env; then
    sed -i "s|^${key}=.*|${key}=${val}|" .env
  else
    printf '%s=%s\n' "$key" "$val" >> .env
  fi
}

if [ -f .env ]; then
  warn ".env 已存在，保留原有密钥不动"
  warn "覆盖它会让用户已保存的 LLM API Key 解不开，也会让所有人的登录态失效"
else
  cp .env.example .env
  set_env MYSQL_ROOT_PASSWORD "$(gen_secret)"
  set_env JWT_SECRET "$(gen_secret)"
  set_env APP_CRYPTO_KEY "$(gen_secret)"
  chmod 600 .env
  info "已生成 .env 并随机填充三个密钥（文件权限 600）"
fi

# 国内服务器直连 Maven Central / npm 官方源经常慢到超时，补上镜像
if ! grep -qE '^MAVEN_MIRROR_URL=' .env; then
  printf '\nMAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public\n' >> .env
  info "已启用 Maven 阿里云镜像"
fi
if ! grep -qE '^NPM_REGISTRY=' .env; then
  printf 'NPM_REGISTRY=https://registry.npmmirror.com\n' >> .env
  info "已启用 npm 国内镜像"
fi

# ---------- 构建启动 ----------
step "构建并启动服务"
info "首次构建要编译后端 + 打包前端，2核2G 的机器大约 8~15 分钟，请耐心等待"
info "中间卡在 Pulling / Downloading 属于正常现象"

# 并行构建会让 Maven 和 Vite 同时抢内存，小内存机器直接 OOM
if [ "$MEM_MB" -lt 4096 ]; then
  export COMPOSE_PARALLEL_LIMIT=1
  info "内存小于 4GB，已改为串行构建以降低内存峰值"
fi

docker compose up -d --build

# ---------- 等待就绪 ----------
step "等待服务就绪"
READY=0
for i in $(seq 1 90); do
  if curl -fsS --max-time 3 "http://127.0.0.1:${WEB_PORT}/api/health" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 5
done

if [ "$READY" -ne 1 ]; then
  warn "等待超时（约 7 分钟仍未就绪）"
  echo
  echo "排查顺序："
  echo "  docker compose ps                  # 看哪个容器没起来"
  echo "  docker compose logs --tail=80 backend"
  echo "  docker compose logs --tail=40 mysql"
  exit 1
fi
info "健康检查通过"

# ---------- 输出访问信息 ----------
PUBLIC_IP="$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null \
  || curl -fsS --max-time 5 https://ifconfig.me 2>/dev/null \
  || hostname -I 2>/dev/null | awk '{print $1}' \
  || echo '<服务器公网IP>')"

cat <<EOF

============================================================
  SelfFace 已启动
    本机：  http://127.0.0.1:${WEB_PORT}
    外网：  http://${PUBLIC_IP}:${WEB_PORT}
============================================================

还需要在云厂商控制台放行端口，否则外网打不开：
  · 腾讯云轻量：服务器详情 → 防火墙 → 添加规则 → TCP ${WEB_PORT}
  · 阿里云轻量：服务器详情 → 防火墙 → 添加规则 → TCP ${WEB_PORT}
  · 阿里云 ECS ：安全组 → 入方向 → 添加 TCP ${WEB_PORT}

常用命令（在项目目录执行）：
  docker compose ps                     查看状态
  docker compose logs -f backend        跟踪后端日志
  docker compose logs -f caddy          跟踪 HTTPS 证书日志
  docker compose restart backend        重启后端
  docker compose down                   停止（数据保留在数据卷里）

下一步（备案通过后切域名 + HTTPS）：
  1) 在 .env 里填 APP_DOMAIN=你的域名 和 ICP_BEIAN=你的备案号
  2) docker compose --profile tls up -d --build
  详见 docs/部署手册.md

EOF
