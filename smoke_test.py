"""
端到端冒烟测试：注册 -> 登录 -> 题库 -> 今日题单 -> 提交作答 -> 统计 -> 错题本。
用法：先启动后端，再执行  python smoke_test.py
"""
import json
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8081/api"
# 显式禁用代理，否则本机请求可能被环境变量里的 http_proxy 劫持
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

PASSED, FAILED = [], []


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with OPENER.open(req, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"code": e.code, "msg": e.read().decode("utf-8", "replace")[:300], "data": None}
    except Exception as e:  # noqa: BLE001
        return {"code": -1, "msg": f"{type(e).__name__}: {e}", "data": None}


def check(name, ok, detail=""):
    (PASSED if ok else FAILED).append(name)
    print(f"{'[OK]  ' if ok else '[FAIL]'} {name}" + (f"  -> {detail}" if detail else ""))


print("=" * 62)
print(" SelfFace · 端到端冒烟测试")
print("=" * 62)

# 1. 注册
#    每次用全新的随机账号，保证脚本可重复运行、不受历史刷题数据干扰
USER = "smoke_" + time.strftime("%m%d_%H%M%S")
PWD = "zhang123456"
r = call("POST", "/auth/register", {
    "username": USER,
    "password": PWD,
    "nickname": "张同学",
    "email": "test@example.com",
})
if r.get("code") == 0:
    check("注册新用户（含中文昵称）", True, f"userId={r['data']['user']['id']} 昵称={r['data']['user']['nickname']}")
elif "已被占用" in str(r.get("msg", "")):
    r = call("POST", "/auth/login", {"username": USER, "password": PWD})
    check("用户已存在，改用登录", r.get("code") == 0)
else:
    check("注册新用户", False, str(r.get("msg"))[:160])

token = (r.get("data") or {}).get("token")
if not token:
    print("\n拿不到 token，后续用例无法继续。")
    raise SystemExit(1)

# 2. 当前用户
r = call("GET", "/auth/me", token=token)
check("读取当前用户信息", r.get("code") == 0, f"{r.get('data', {}).get('nickname')} 目标岗位={r.get('data', {}).get('targetPosition')}")

# 3. 分类
r = call("GET", "/categories", token=token)
cats = r.get("data") or []
total_q = sum(c.get("count", 0) for c in cats)
check("拉取分类与题数", len(cats) == 11 and total_q == 112,
      f"{len(cats)} 个分类 / {total_q} 道题")

# 4. 题库检索
r = call("GET", "/questions?keyword=HashMap&page=1&size=5", token=token)
page = r.get("data") or {}
check("按关键词检索题库", page.get("total", 0) >= 3,
      f"命中 {page.get('total')} 条，首条：{(page.get('records') or [{}])[0].get('title', '')[:32]}")
check("列表默认不返回答案", "answer" not in ((page.get("records") or [{}])[0] or {}))

# 5. 题目详情含答案
first_id = (page.get("records") or [{}])[0].get("id")
r = call("GET", f"/questions/{first_id}", token=token)
check("题目详情返回参考答案", bool((r.get("data") or {}).get("answer")),
      f"答案 {len((r.get('data') or {}).get('answer') or '')} 字")

# 6. 今日题单
r = call("GET", "/practice/today", token=token)
tasks = r.get("data") or []
check("生成今日题单（10 题）", len(tasks) == 10, f"{len(tasks)} 题")
if tasks:
    review = sum(1 for t in tasks if t.get("isReview"))
    cats_used = len({t["categoryId"] for t in tasks})
    print(f"       组成：复习题 {review} 道，覆盖 {cats_used} 个分类")

# 7. 重复请求应返回同一份题单
r2 = call("GET", "/practice/today", token=token)
same = [t["questionId"] for t in (r2.get("data") or [])] == [t["questionId"] for t in tasks]
check("重复请求幂等（同一份题单）", same)

# 8. 提交作答：3 题（不会 / 模糊 / 掌握）
submitted = []
for idx, mastery in enumerate([1, 2, 3]):
    if idx >= len(tasks):
        break
    qid = tasks[idx]["questionId"]
    r = call("POST", "/practice/submit", {
        "questionId": qid,
        "mastery": mastery,
        "answerText": f"第 {idx + 1} 题我的口述答案",
        "costSeconds": 45 + idx,
    }, token=token)
    ok = r.get("code") == 0
    submitted.append(ok)
    if ok and idx == 0:
        check("提交作答并返回参考答案", bool(r["data"].get("answer")),
              f"今日进度 {r['data']['todayProgress']['done']}/{r['data']['todayProgress']['total']}")
check("三种掌握状态都能提交", all(submitted) and len(submitted) == 3)

# 9. 统计
r = call("GET", "/practice/stats", token=token)
s = r.get("data") or {}
check("仪表盘统计", s.get("answered", 0) >= 3,
      f"已答 {s.get('answered')} 题 / 掌握 {s.get('mastered')} / 待复习 {s.get('weak')} / "
      f"连续 {s.get('streakDays')} 天 / 覆盖率 {s.get('coverage')}%")
check("分类进度聚合", len(s.get("categories") or []) >= 1, f"{len(s.get('categories') or [])} 个分类有数据")
check("按天趋势聚合", len(s.get("trend") or []) >= 1, f"{len(s.get('trend') or [])} 天有记录")

# 10. 错题本
r = call("GET", "/practice/wrong-book", token=token)
wb = r.get("data") or []
check("错题本只收录不会/模糊的题", len(wb) == 2, f"{len(wb)} 道：{[w['mastery'] for w in wb]}")

# 11. 加入今日题单（挑一道今天还没安排的题）
today_ids = {t["questionId"] for t in (call("GET", "/practice/today", token=token).get("data") or [])}
pool = call("GET", "/questions?page=1&size=50", token=token).get("data") or {}
outside = next((q["id"] for q in pool.get("records", []) if q["id"] not in today_ids), None)
before = len(today_ids)
r = call("POST", "/practice/append", {"questionId": outside}, token=token)
after = len(call("GET", "/practice/today", token=token).get("data") or [])
check("手动追加题目到今日题单", r.get("code") == 0 and after == before + 1,
      f"题单 {before} -> {after} 题")
check("重复追加同一题不会重复加入",
      call("POST", "/practice/append", {"questionId": outside}, token=token).get("code") == 0
      and len(call("GET", "/practice/today", token=token).get("data") or []) == after)

# 12. 权限校验
r = call("GET", "/practice/today")
check("未带 token 访问被拒绝", r.get("code") in (401, 403), f"code={r.get('code')}")

r = call("GET", "/not-exist-path", token=token)
check("不存在的路径返回 404 而非 500", r.get("code") == 404, f"code={r.get('code')} {str(r.get('msg'))[:40]}")

# 13. LLM 配置（未配置时的引导）
r = call("GET", "/llm/setting", token=token)
setting = r.get("data") or {}
check("读取 LLM 配置（初始未配置）", r.get("code") == 0,
      f"configured={setting.get('configured')} baseUrl='{setting.get('baseUrl')}'")

# 14. 未配置就调用简历分析应给出友好提示
r = call("POST", "/llm/test", token=token)
check("未配置时提示去设置页填写", r.get("code") != 0 and "设置" in str(r.get("msg", "")) + str(r.get("data", "")),
      str(r.get("msg", ""))[:80] or str(r.get("data"))[:80])

# 15. 简历分析改成后台任务后，未配置 Key 必须同步失败，且不留下垃圾任务记录
def upload(path, filename, content, token):
    boundary = "----smoke" + str(int(time.time() * 1000))
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode("utf-8") + content + f"\r\n--{boundary}--\r\n".encode("utf-8")
    req = urllib.request.Request(BASE + path, data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", "Bearer " + token)
    try:
        with OPENER.open(req, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"code": e.code, "msg": e.read().decode("utf-8", "replace")[:300], "data": None}


r = upload("/resume/analyze", "resume.txt",
           "张三 软件工程 熟悉 Java SpringBoot MySQL".encode("utf-8"), token)
check("未配置 Key 时上传简历立刻失败并提示去设置页",
      r.get("code") != 0 and "设置" in str(r.get("msg", "")) + str(r.get("data", "")),
      str(r.get("msg", ""))[:70] or str(r.get("data"))[:70])

r = call("GET", "/resume/list", token=token)
check("被拒绝的上传不会留下垃圾任务记录", r.get("code") == 0 and len(r.get("data") or []) == 0,
      f"历史记录 {len(r.get('data') or [])} 条")

print("-" * 62)
print(f" 通过 {len(PASSED)} 项，失败 {len(FAILED)} 项")
if FAILED:
    print(" 失败项：" + "、".join(FAILED))
print("=" * 62)
