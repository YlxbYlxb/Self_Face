#!/usr/bin/env bash
# 清理 verify_new_features.py 留下的验证数据。
#
# 删除范围：code 以 verify- 开头的分类（也就是「验证分类 …」）及其下题目。
# 口令从 backend/.env.local 读取，不出现在命令行上。
#
# 用法：
#   bash scripts/cleanup-verify-data.sh
# 若 mysql 客户端不在 PATH 里，指定完整路径：
#   MYSQL_BIN="/path/to/mysql" bash scripts/cleanup-verify-data.sh
set -euo pipefail
cd "$(dirname "$0")/../backend"

if [ -f .env.local ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env.local
  set +a
fi
: "${DB_PASSWORD:?请先在 backend/.env.local 中设置 DB_PASSWORD}"

MYSQL="${MYSQL_BIN:-mysql}"
export MYSQL_PWD="${DB_PASSWORD}"

"${MYSQL}" \
  -u"${DB_USERNAME:-root}" \
  -h"${DB_HOST:-127.0.0.1}" \
  -P"${DB_PORT:-3306}" \
  "${DB_NAME:-selfface}" -e "
DELETE q FROM question q
  JOIN category c ON q.category_id = c.id
  WHERE c.code LIKE 'verify-%' OR c.name LIKE '验证分类%';
DELETE FROM category WHERE code LIKE 'verify-%' OR name LIKE '验证分类%';
SELECT 'categories' AS item, COUNT(*) AS total FROM category
UNION ALL SELECT 'questions', COUNT(*) FROM question;
"
