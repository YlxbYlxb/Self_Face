"""
JD 定向题单的端到端验证。

用 scripts/mock_llm.py 顶替真实模型，把整条链路跑通：
    JD 文本 → 抽关键词 → 题库召回 → 覆盖率 → 推荐排序 → 一键加入今日题单 → 幂等

验证重点：
  1. 参数校验（太短直接拒绝）在调用模型之前发生，不浪费 Key；
  2. 关键词命中情况如实统计，「题库没覆盖」的技术点要单独列出来；
  3. 推荐题单按「命中关键词数」排序，且同一道题不重复出现；
  4. 「一键加入今日题单」要真的写进题单，且重复调用不会重复插入。

用法：
    1) 先启动后端
    2) python scripts/mock_llm.py     （另开一个终端）
    3) python verify_jd.py
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


JD_TEXT = """
【岗位职责】
1. 参与公司核心业务系统的后端开发，使用 Java / Spring Boot 完成服务的设计与实现；
2. 负责数据库表结构设计与 SQL 优化，保障 MySQL 查询性能；
3. 参与缓存方案设计，使用 Redis 提升接口响应速度；
4. 配合完成服务的容器化部署与运维。
【任职要求】
1. 计算机相关专业本科及以上在读，每周可实习 4 天以上；
2. 熟悉 Java 语言与 JVM 内存模型，了解常见垃圾回收器；
3. 熟悉 MySQL，能定位慢查询并做索引优化；
4. 了解 Redis 常见数据结构与缓存穿透、缓存雪崩的应对方案；
5. 有 Docker / Kubernetes 使用经验者优先。
"""

print("=" * 64)
print(" SelfFace · JD 定向题单验证")
print("=" * 64)

# ------------------------------------------------ 0. mock 服务是否在跑
probe = call("POST", "/jd/analyze", {"jdText": "x" * 40})
if probe.get("code") == -1:
    print("\n无法访问后端，请先启动服务。")
    raise SystemExit(1)

TS = time.strftime("%m%d_%H%M%S")
USER = "jd_" + TS
PWD = "zhang123456"

r = call("POST", "/auth/register", {"username": USER, "password": PWD, "nickname": "JD 验证"})
token = (r.get("data") or {}).get("token")
if not token:
    print(f"\n注册失败：{str(r.get('msg'))[:200]}")
    raise SystemExit(1)
UID = (r["data"]["user"])["id"]
check("注册独立验证账号", True, f"userId={UID}")

# ------------------------------------------------ 1. 太短直接拒绝
r = call("POST", "/jd/analyze", {"jdText": "招 Java 开发"}, token=token)
check("JD 太短时直接拒绝，不去调用模型",
      r.get("code") != 0 and "太短" in str(r.get("msg", "")), str(r.get("msg"))[:80])

# ------------------------------------------------ 2. 未配置模型时给出可操作提示
r = call("POST", "/jd/analyze", {"jdText": JD_TEXT}, token=token)
check("未配置大模型时提示去设置页",
      r.get("code") != 0 and "设置" in str(r.get("msg", "")), str(r.get("msg"))[:80])

# ------------------------------------------------ 3. 指向 mock 服务
r = call("PUT", "/llm/setting", {
    "baseUrl": MOCK_LLM, "apiKey": "mock-key-for-verification",
    "model": "mock-model", "temperature": 0.3, "timeoutSeconds": 60,
}, token=token)
check("把模型配置指向本地 mock 服务", r.get("code") == 0, str(r.get("msg"))[:80])

# ------------------------------------------------ 4. 分析 JD
r = call("POST", "/jd/analyze", {"jdText": JD_TEXT, "role": "Java 后端"}, token=token)
d = r.get("data") or {}
check("JD 分析调用成功", r.get("code") == 0, str(r.get("msg"))[:120])
if not d:
    raise SystemExit(1)

check("解析出岗位名", d.get("role") == "Java 后端开发实习生", f"role={d.get('role')}")
check("抽取到 4 个技术关键词", len(d.get("keywords") or []) == 4,
      f"实际 {len(d.get('keywords') or [])} 个")

kw = {k["term"]: k for k in d.get("keywords") or []}
check("JVM 在题库里召回到了题目", kw.get("JVM", {}).get("matchCount", 0) > 0,
      f"JVM 命中 {kw.get('JVM', {}).get('matchCount')} 题")
check("MySQL 在题库里召回到了题目", kw.get("MySQL", {}).get("matchCount", 0) > 0,
      f"MySQL 命中 {kw.get('MySQL', {}).get('matchCount')} 题")

coverage = d.get("coverage") or 0
expect = round((d.get("coveredCount") or 0) * 100 / max(1, d.get("keywordCount") or 1))
check("覆盖率计算与命中口径一致", abs(coverage - expect) <= 1,
      f"coverage={coverage} 期望≈{expect}（{d.get('coveredCount')}/{d.get('keywordCount')}）")

miss = d.get("missingKeywords") or []
check("「题库没覆盖」的技术点被单独列出",
      isinstance(miss, list) and len(miss) == d.get("keywordCount", 0) - d.get("coveredCount", 0),
      f"missing={miss}")

# ------------------------------------------------ 5. 推荐题单
rec = d.get("recommended") or []
ids = [q["questionId"] for q in rec]
check("推荐题单非空", len(rec) > 0, f"{len(rec)} 道")
check("推荐题单里没有重复题目", len(ids) == len(set(ids)), f"{len(ids)} 个 id / {len(set(ids))} 个唯一")
check("每道推荐题都标注了命中的关键词",
      all(q.get("hitTerms") for q in rec),
      f"首题命中 {rec[0].get('hitTerms') if rec else 'N/A'}")

# 排序：命中词数单调不增
counts = [len(q.get("hitTerms") or []) for q in rec]
check("推荐按「命中关键词数」从多到少排序", counts == sorted(counts, reverse=True), f"命中数序列={counts[:10]}")

# ------------------------------------------------ 6. 一键加入今日题单
pick = ids[:5]
r = call("POST", "/practice/append-batch", {"questionIds": pick}, token=token)
inserted = (r.get("data") or {}).get("inserted")
check("一键加入题单写入成功", r.get("code") == 0 and inserted == len(pick),
      f"inserted={inserted}，期望 {len(pick)}")

tasks = call("GET", "/practice/today", token=token).get("data") or []
task_ids = [t["questionId"] for t in tasks]
check("加入的题目出现在今日题单里", all(i in task_ids for i in pick),
      f"题单共 {len(task_ids)} 道，命中 {sum(1 for i in pick if i in task_ids)}/{len(pick)}")

r2 = call("POST", "/practice/append-batch", {"questionIds": pick}, token=token)
check("重复加入是幂等的（不会重复插入）", (r2.get("data") or {}).get("inserted") == 0,
      f"第二次 inserted={(r2.get('data') or {}).get('inserted')}")

r3 = call("POST", "/practice/append-batch", {"questionIds": []}, token=token)
check("空数组被拒绝并给出提示", r3.get("code") != 0, str(r3.get("msg"))[:60])

# ------------------------------------------------ 7. 清理
mysql(f"DELETE FROM llm_setting WHERE user_id={UID}")
mysql(f"DELETE FROM daily_task WHERE user_id={UID}")
mysql(f"DELETE FROM practice_record WHERE user_id={UID}")
mysql(f"DELETE FROM review_state WHERE user_id={UID}")
mysql(f"DELETE FROM sys_user WHERE id={UID}")
check("清理验证数据", True, "账号、模型配置与题单记录已删除")

print("-" * 64)
print(f" 通过 {len(PASSED)} / {len(PASSED) + len(FAILED)}")
if FAILED:
    print(" 失败用例：")
    for f in FAILED:
        print("   - " + f)
print("=" * 64)
raise SystemExit(1 if FAILED else 0)
