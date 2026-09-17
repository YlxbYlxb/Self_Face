"""
部署前安全自检。

用法：  python scripts/check_security.py
退出码：0 = 通过；1 = 有问题

只查几件最容易出事的低级问题：带着仓库里公开的开发密钥上线、
把 .env 提交进仓库、口令硬编码在配置或脚本里。
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG = ROOT / "backend" / "src" / "main" / "resources" / "application.yml"
PROD_CONFIG = ROOT / "backend" / "src" / "main" / "resources" / "application-prod.yml"
GITIGNORE = ROOT / ".gitignore"
COMPOSE = ROOT / "docker-compose.yml"

# 这些值已经随仓库公开，任何人都能拿它们伪造登录态或解开密文
DEV_SECRETS = {
    "selfface-dev-secret-please-change-it-2026": "JWT 开发密钥",
    "selfface-dev-crypto-key-please-change-2026": "API Key 加密开发密钥",
}

problems: list[str] = []
notes: list[str] = []


def fail(message: str) -> None:
    problems.append(message)


def ok(message: str) -> None:
    notes.append(message)


# 1) 配置里的密钥必须写成「环境变量优先」的形式，且不能被硬编码为实值
if not CONFIG.is_file():
    fail(f"找不到配置文件：{CONFIG}")
else:
    text = CONFIG.read_text(encoding="utf-8")

    for value, label in DEV_SECRETS.items():
        # 允许出现在 ${VAR:默认值} 里（开发兜底），但不能作为独立的值写死
        pattern = re.compile(rf"^\s*[a-z-]+:\s*{re.escape(value)}\s*$", re.MULTILINE)
        if pattern.search(text):
            fail(f"application.yml 里 {label} 被硬编码，应当写成 ${{环境变量:默认值}}")
        elif f"${{" in text and value in text:
            ok(f"{label}使用环境变量覆盖，内置值只作开发兜底")

    for var in ("JWT_SECRET", "APP_CRYPTO_KEY"):
        if var not in text:
            fail(f"application.yml 未引用环境变量 {var}，生产环境将无法覆盖密钥")

    # 数据源口令不能有非空默认值
    if re.search(r"password:\s*\$\{DB_PASSWORD:([^}]+)\}", text):
        fail("application.yml 中 DB_PASSWORD 带有非空默认值，口令不能写进配置")
    else:
        ok("数据库口令默认留空，必须由外部提供")

# 2) 生产 profile 必须存在
if PROD_CONFIG.is_file():
    ok("存在 application-prod.yml，生产环境有独立配置")
else:
    fail("缺少 application-prod.yml，生产环境没有独立的日志与跨域策略")

# 3) 敏感文件必须被忽略
if not GITIGNORE.is_file():
    fail("缺少 .gitignore")
else:
    ignored = GITIGNORE.read_text(encoding="utf-8")
    for entry in (".env", "backend/.env.local"):
        if entry in ignored:
            ok(f".gitignore 已忽略 {entry}")
        else:
            fail(f".gitignore 未忽略 {entry}，密钥有被提交的风险")

# 4) 仓库里不应出现除示例之外的 .env 文件
for path in ROOT.rglob(".env"):
    if ".git" in path.parts or "node_modules" in path.parts:
        continue
    if path.name != ".env.example":
        fail(f"发现未忽略的环境文件：{path.relative_to(ROOT)}")

# 5) compose 里三个密钥都必须强校验，缺失时拒绝启动
if COMPOSE.is_file():
    compose = COMPOSE.read_text(encoding="utf-8")
    for var in ("MYSQL_ROOT_PASSWORD", "JWT_SECRET", "APP_CRYPTO_KEY"):
        if f"${{{var}:?" in compose:
            ok(f"docker-compose.yml 对 {var} 做了必填校验")
        else:
            fail(f"docker-compose.yml 未对 {var} 做必填校验（应写成 ${{{var}:?提示}}）")
else:
    fail("缺少 docker-compose.yml，没有可一键启动的部署入口")

print("安全检查结果：")
for note in notes:
    print("  [OK]  " + note)
for problem in problems:
    print("  [FAIL] " + problem)

if problems:
    print(f"\n共 {len(problems)} 项需要处理")
    sys.exit(1)

print(f"\n全部 {len(notes)} 项检查通过")
sys.exit(0)
