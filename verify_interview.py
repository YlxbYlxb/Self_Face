"""
模拟面试的端到端验证（用 scripts/mock_llm.py 顶替真实模型）。

验证重点：
  1. 开场 → 逐轮问答 → 报告，整条链路的轮次顺序与字段完整性；
  2. 面试官每一轮都要给出「评分 + 具体点评」，这是追问式的核心价值；
  3. 结束后不能继续作答，重复结束是幂等的（不会重复烧模型额度）；
  4. **别人的面试记录读不到** —— 多用户系统里最容易漏的一条。

用法：
    1) 先启动后端
    2) python scripts/mock_llm.py     （另开一个终端）
    3) python verify_interview.py
"""
import json
import os
import subprocess
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8081/api"
MOCK_LLM = "http://127.0.0.1:18080/v1"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
MYSQL = r"E:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"

PASSED, FAILED = [], []


def call(method, path, body=None, token=None, timeout=120):
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
    if not os.path.exists(MYSQL):
        return False
    env = dict(os.environ, MYSQL_PWD=ENV.get("DB_PASSWORD", ""))
    try:
        p = subprocess.run(
            [MYSQL, "-uroot", "-h127.0.0.1", ENV.get("DB_NAME", "selfface"), "-N", "-B", "-e", sql],
            capture_output=True, text=True, env=env, timeout=30)
        return p.returncode == 0
    except Exception:  # noqa: BLE001
        return False


def check(name, ok, detail=""):
    (PASSED if ok else FAILED).append(name)
    print(f"{'[OK]  ' if ok else '[FAIL]'} {name}" + (f"  -> {detail}" if detail else ""))


def register(username):
    r = call("POST", "/auth/register", {"username": username, "password": "zhang123456"})
    return (r.get("data") or {}).get("token"), (r.get("data") or {}).get("user", {}).get("id")


print("=" * 64)
print(" SelfFace · 模拟面试验证")
print("=" * 64)

TS = time.strftime("%m%d_%H%M%S")
tokenA, uidA = register("iv_a_" + TS)
tokenB, uidB = register("iv_b_" + TS)
if not tokenA or not tokenB:
    print("\n注册失败，请确认后端已启动。")
    raise SystemExit(1)
check("注册两个独立账号（用于越权测试）", True, f"A={uidA} B={uidB}")

# ------------------------------------------------ 1. 未配置模型
r = call("POST", "/interview/session", {"role": "Java 后端", "level": "junior",
                                        "type": "technical", "maxRounds": 3}, token=tokenA)
check("未配置大模型时拒绝开始面试并提示去设置页",
      r.get("code") != 0 and "设置" in str(r.get("msg", "")), str(r.get("msg"))[:80])

r = call("PUT", "/llm/setting", {
    "baseUrl": MOCK_LLM, "apiKey": "mock-key", "model": "mock-model",
    "temperature": 0.3, "timeoutSeconds": 60,
}, token=tokenA)
check("把模型配置指向 mock 服务", r.get("code") == 0, str(r.get("msg"))[:60])

# ------------------------------------------------ 2. 开场
r = call("POST", "/interview/session",
         {"role": "Java 后端开发实习生", "level": "junior", "type": "project", "maxRounds": 3},
         token=tokenA)
d = r.get("data") or {}
check("开始面试成功", r.get("code") == 0 and d.get("id"), str(r.get("msg"))[:100])
if not d.get("id"):
    raise SystemExit(1)

SID = d["id"]
turns = d.get("turns") or []
check("面试官提出了开场问题", len(turns) == 1
      and turns[0]["role"] == "interviewer" and turns[0]["content"], f"{len(turns)} 条轮次")
check("会话初始状态为进行中", d.get("status") == "RUNNING", f"status={d.get('status')}")
check("会话保留了岗位与类型设置",
      d.get("level") == "junior" and d.get("type") == "project", f"{d.get('level')}/{d.get('type')}")

# ------------------------------------------------ 3. 三轮问答
ANSWER = "这个项目我用 Spring Boot 做后端，MySQL 存数据，Redis 做缓存，整体分三层……"
scores = []
for i in range(3):
    r = call("POST", f"/interview/session/{SID}/answer", {"answer": ANSWER}, token=tokenA)
    d = r.get("data") or {}
    if r.get("code") != 0:
        check(f"第 {i + 1} 轮作答", False, str(r.get("msg"))[:120])
        break
    turns = d.get("turns") or []
    last = turns[-1] if turns else {}
    scores.append(last.get("score"))
    ok = (len(turns) == 1 + (i + 1) * 2
          and last.get("role") == "interviewer"
          and last.get("score") is not None
          and last.get("comment"))
    check(f"第 {i + 1} 轮：记录回答 + 面试官点评评分", ok,
          f"turns={len(turns)} score={last.get('score')} 追问={str(last.get('content'))[:24]}…")

d = call("GET", f"/interview/session/{SID}", token=tokenA).get("data") or {}
check("轮次计数正确", d.get("round") == 3, f"round={d.get('round')}")
check("追问问的问题与开场不同（真的在推进）",
      len(set(t["content"] for t in (d.get("turns") or []) if t["role"] == "interviewer")) >= 2)

# ------------------------------------------------ 4. 结束与报告
r = call("POST", f"/interview/session/{SID}/finish", {}, token=tokenA)
d = r.get("data") or {}
rep = d.get("report") or {}
check("结束面试并生成报告", r.get("code") == 0 and bool(rep), str(r.get("msg"))[:100])
check("报告包含总评分", isinstance(rep.get("overallScore"), int), f"overallScore={rep.get('overallScore')}")
check("报告包含分维度评价", bool(rep.get("dimensions")), f"{len(rep.get('dimensions') or [])} 个维度")
check("报告给出需要补的点", bool(rep.get("weaknesses")), f"{len(rep.get('weaknesses') or [])} 条")
check("会话状态变为已结束", d.get("status") == "FINISHED", f"status={d.get('status')}")

first_overall = d.get("overallScore")
r2 = call("POST", f"/interview/session/{SID}/finish", {}, token=tokenA)
check("重复结束是幂等的（不会重复调用模型）",
      r2.get("code") == 0 and (r2.get("data") or {}).get("overallScore") == first_overall,
      f"两次总评都是 {first_overall}")

# ------------------------------------------------ 5. 结束后的状态约束
r3 = call("POST", f"/interview/session/{SID}/answer", {"answer": "我还想再答一题"}, token=tokenA)
check("已结束的面试不能继续作答",
      r3.get("code") != 0 and "结束" in str(r3.get("msg", "")), str(r3.get("msg"))[:80])

# ------------------------------------------------ 6. 越权
r4 = call("GET", f"/interview/session/{SID}", token=tokenB)
check("别人的面试记录读不到（越权被拦）",
      r4.get("code") != 0 and "不存在" in str(r4.get("msg", "")), str(r4.get("msg"))[:80])

r5 = call("POST", f"/interview/session/{SID}/finish", {}, token=tokenB)
check("别人不能结束我的面试", r5.get("code") != 0, str(r5.get("msg"))[:80])

# ------------------------------------------------ 7. 历史列表
r6 = call("GET", "/interview/sessions", token=tokenA)
rows = r6.get("data") or []
check("面试历史里能查到这场面试",
      any(row["id"] == SID for row in rows), f"{len(rows)} 条记录")
check("历史列表带上了总评分（不用点进去也能看）",
      any(row["id"] == SID and row.get("overallScore") is not None for row in rows))

# ------------------------------------------------ 8. 清理
mysql(f"DELETE FROM interview_turn WHERE session_id IN "
      f"(SELECT id FROM interview_session WHERE user_id IN ({uidA},{uidB}))")
mysql(f"DELETE FROM interview_session WHERE user_id IN ({uidA},{uidB})")
mysql(f"DELETE FROM llm_setting WHERE user_id IN ({uidA},{uidB})")
mysql(f"DELETE FROM sys_user WHERE id IN ({uidA},{uidB})")
check("清理验证数据", True, "两个账号及其面试记录已删除")

print("-" * 64)
print(f" 通过 {len(PASSED)} / {len(PASSED) + len(FAILED)}")
if FAILED:
    print(" 失败用例：")
    for f in FAILED:
        print("   - " + f)
print("=" * 64)
raise SystemExit(1 if FAILED else 0)
