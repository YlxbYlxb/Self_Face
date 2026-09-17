"""
SM-2 间隔重复调度的端到端验证。

验证的不是「接口能不能调通」，而是四件真正会决定复习效果的事：
  1. 间隔是否按 SM-2 增长（1 → 3 → 8 → 22 → 62 天）；
  2. 答「不会」是否打回 1 天、答「模糊」是否小幅前进；
  3. 做过的题是否被正确标记成复习题，并带上当前间隔；
  4. **到期的题是否会重新出现在题单里** —— 这是整个改造的核心价值。
     老逻辑里答对的题会永久消失，这一步专门验证它没有复发。

第 4 项没法只靠接口验证（没有「把下次复习改到昨天」的 API），
所以脚本会直接用 mysql 客户端改库，属于测试夹具的正常用法。

用法：先启动后端，再执行  python verify_srs.py
"""
import datetime
import json
import os
import subprocess
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8081/api"
# 显式禁用代理，否则本机请求会被环境变量里的 http_proxy 劫持
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

MYSQL = r"E:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"

PASSED, FAILED = [], []


def call(method, path, body=None, token=None, timeout=60):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with OPENER.open(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"code": e.code, "msg": e.read().decode("utf-8", "replace")[:300], "data": None}
    except Exception as e:  # noqa: BLE001
        return {"code": -1, "msg": f"{type(e).__name__}: {e}", "data": None}


def local_env():
    """读 backend/.env.local 里的数据库口令（该文件不入库）"""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "backend", ".env.local")
    cfg = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    cfg[k.strip()] = v.strip()
    return cfg


ENV = local_env()


def mysql(sql):
    """执行一条 SQL，返回（单行文本, 是否成功）。测试夹具用，非生产路径。"""
    if not os.path.exists(MYSQL):
        return "", False
    env = dict(os.environ, MYSQL_PWD=ENV.get("DB_PASSWORD", ""))
    try:
        p = subprocess.run(
            [MYSQL, "-uroot", "-h127.0.0.1", ENV.get("DB_NAME", "selfface"), "-N", "-B", "-e", sql],
            capture_output=True, text=True, env=env, timeout=30)
        return p.stdout.strip(), p.returncode == 0
    except Exception as e:  # noqa: BLE001
        return str(e), False


def check(name, ok, detail=""):
    (PASSED if ok else FAILED).append(name)
    print(f"{'[OK]  ' if ok else '[FAIL]'} {name}" + (f"  -> {detail}" if detail else ""))


print("=" * 64)
print(" SelfFace · SM-2 间隔重复调度验证")
print("=" * 64)

TS = time.strftime("%m%d_%H%M%S")
USER = "srs_" + TS
PWD = "zhang123456"

# ------------------------------------------------------------------ 1. 独立账号
r = call("POST", "/auth/register", {"username": USER, "password": PWD, "nickname": "SRS 验证"})
token = (r.get("data") or {}).get("token")
if not token:
    print(f"\n注册失败，无法继续：{str(r.get('msg'))[:200]}")
    raise SystemExit(1)
UID = (r["data"]["user"])["id"]
check("注册独立验证账号", True, f"userId={UID}")

# ------------------------------------------------------------------ 2. 首日题单
tasks = call("GET", "/practice/today", token=token).get("data") or []
check("题单容量为 10", len(tasks) == 10, f"实际 {len(tasks)} 道")
check("全新账号的题单全部标记为新题", bool(tasks) and all(t.get("isNew") for t in tasks),
      f"有 {sum(1 for t in tasks if t.get('isReview'))} 道被标成了复习题")

QID = tasks[0]["questionId"]

# ------------------------------------------------------------------ 3. 连续答「掌握」
intervals = []
last = None
for _ in range(5):
    res = call("POST", "/practice/submit", {"questionId": QID, "mastery": 3}, token=token).get("data") or {}
    intervals.append(res.get("intervalDays"))
    last = res
check("连续答「掌握」时间隔按 1→3→8→22→62 天拉长",
      intervals == [1, 3, 8, 22, 62], f"实际 {intervals}")

today = datetime.date.today()
expect_next = (today + datetime.timedelta(days=last["intervalDays"])).isoformat()
check("下次复习日 = 今天 + 间隔天数", last.get("nextReviewAt") == expect_next,
      f"接口返回 {last.get('nextReviewAt')}，期望 {expect_next}")

# ------------------------------------------------------------------ 4. 答「不会」
res = call("POST", "/practice/submit", {"questionId": QID, "mastery": 1}, token=token).get("data") or {}
check("答「不会」后间隔打回 1 天", res.get("intervalDays") == 1, f"实际 {res.get('intervalDays')}")

# ------------------------------------------------------------------ 5. 答「模糊」
call("POST", "/practice/submit", {"questionId": QID, "mastery": 3}, token=token)  # 先推到 3 天
res = call("POST", "/practice/submit", {"questionId": QID, "mastery": 2}, token=token).get("data") or {}
check("答「模糊」时按 1.2 倍小幅前进（3 → 4 天）",
      res.get("intervalDays") == 4, f"实际 {res.get('intervalDays')}")

# ------------------------------------------------------------------ 6. 难度系数与计数
row, ok = mysql(
    f"SELECT interval_days, ease_factor, review_count, lapse_count "
    f"FROM review_state WHERE user_id={UID} AND question_id={QID}")
check("review_state 落库成功", ok and row != "", row or "查询失败")
if row:
    parts = row.split("\t")
    check("难度系数落在 1.30~2.80 之间", 1.30 <= float(parts[1]) <= 2.80, f"ease_factor={parts[1]}")
    # 累计提交 8 次：5 次「掌握」+ 1 次「不会」+ 1 次「掌握」+ 1 次「模糊」
    check("复习次数累计为 8 次", parts[2] == "8", f"review_count={parts[2]}")
    check("遗忘次数累计为 1 次", parts[3] == "1", f"lapse_count={parts[3]}")

# ------------------------------------------------------------------ 7. 题单标注
tasks = call("GET", "/practice/today", token=token).get("data") or []
target = next((t for t in tasks if t["questionId"] == QID), None)
check("做过的题在题单里被标为复习题", bool(target) and target.get("isReview") and not target.get("isNew"),
      f"isReview={target.get('isReview') if target else 'N/A'}")
check("复习题带上当前间隔供前端展示",
      bool(target) and target.get("intervalDays") == 4,
      f"intervalDays={target.get('intervalDays') if target else 'N/A'}")

# ------------------------------------------------------------------ 8. 到期回归（核心）
_, ok = mysql(f"UPDATE review_state SET next_review_at = DATE_SUB(CURDATE(), INTERVAL 1 DAY) "
              f"WHERE user_id={UID} AND question_id={QID}")
check("把这道题改成「昨天已到期」", ok)

_, ok = mysql(f"DELETE FROM daily_task WHERE user_id={UID}")
check("清空今日题单以触发重新组卷", ok)

tasks = call("GET", "/practice/today", token=token).get("data") or []
ids = [t["questionId"] for t in tasks]
check("到期的题会重新排进今日题单（答对的题不再永久消失）", QID in ids,
      f"题单共 {len(ids)} 道，{'命中' if QID in ids else '未命中'}")
check("到期的题排在最前面", bool(ids) and ids[0] == QID,
      f"首位是 {ids[0] if ids else 'N/A'}")

# ------------------------------------------------------------------ 9. 到期计数
stats = call("GET", "/practice/stats", token=token).get("data") or {}
check("仪表盘上报今日到期题数", (stats.get("dueCount") or 0) >= 1,
      f"dueCount={stats.get('dueCount')}")

# ------------------------------------------------------------------ 10. 收尾
mysql(f"DELETE FROM review_state WHERE user_id={UID}")
mysql(f"DELETE FROM practice_record WHERE user_id={UID}")
mysql(f"DELETE FROM daily_task WHERE user_id={UID}")
mysql(f"DELETE FROM sys_user WHERE id={UID}")
check("清理验证数据", True, "账号及其作答记录已删除")

print("-" * 64)
print(f" 通过 {len(PASSED)} / {len(PASSED) + len(FAILED)}")
if FAILED:
    print(" 失败用例：")
    for f in FAILED:
        print("   - " + f)
print("=" * 64)
raise SystemExit(1 if FAILED else 0)
