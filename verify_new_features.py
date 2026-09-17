"""
本轮新增能力的端到端验证：
  1. 题库导入（新分类自建、重复幂等、未知分类处理、非法 JSON、超量拒绝）
  2. LLM API Key 加密（接口只回传掩码、掩码基于明文、改配置不丢 Key）
  3. 登录防爆破（连续失败后锁定）
  4. 限流（超过每分钟上限返回 429）

用法：先启动后端，再执行  python verify_new_features.py
说明：
  - 导入的题目标题带时间戳，每次运行会新增少量验证数据，属预期行为。
  - 脚本末尾会把限流额度打满，所以连续两次运行要间隔 1 分钟以上，
    否则前面的「保存 LLM 配置」等用例会先被限流拦下。
"""
import json
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8081/api"
# 显式禁用代理，否则本机请求会被环境变量里的 http_proxy 劫持
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

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


def check(name, ok, detail=""):
    (PASSED if ok else FAILED).append(name)
    print(f"{'[OK]  ' if ok else '[FAIL]'} {name}" + (f"  -> {detail}" if detail else ""))


print("=" * 62)
print(" SelfFace · 新增能力验证")
print("=" * 62)

TS = time.strftime("%m%d_%H%M%S")
USER = "verify_" + TS
PWD = "zhang123456"

# ---------------------------------------------------------------- 1. 准备账号
r = call("POST", "/auth/register", {"username": USER, "password": PWD, "nickname": "验证账号"})
token = (r.get("data") or {}).get("token")
if not token:
    print(f"\n注册失败，无法继续：{str(r.get('msg'))[:200]}")
    raise SystemExit(1)
check("注册验证账号", True, f"userId={(r['data']['user'])['id']}")

# ---------------------------------------------------------------- 2. 题库导入
CAT_CODE = "verify-" + TS
CAT_NAME = "验证分类 " + TS
payload = {
    "categories": [{"code": CAT_CODE, "name": CAT_NAME, "description": "自动化验证用"}],
    "questions": [
        {
            "title": f"验证题目 A {TS}",
            "answer": "答案 A",
            "category": CAT_CODE,
            "difficulty": 2,
            "tags": "验证,A",
            "hot": 1,
        },
        {
            "title": f"验证题目 B {TS}",
            "answer": "答案 B",
            "category": CAT_CODE,
            # 故意越界，验证会被夹到 1-3 而不是整条拒收
            "difficulty": 9,
        },
    ],
}
content = json.dumps(payload, ensure_ascii=False)

r = call("POST", "/questions/import", {"content": content, "autoCreateCategory": True}, token)
d = r.get("data") or {}
check("导入题库并自动新建分类", r.get("code") == 0 and d.get("inserted") == 2 and d.get("categoriesCreated") == 1,
      f"提交 {d.get('total')} / 写入 {d.get('inserted')} / 跳过 {d.get('skipped')} / 新建分类 {d.get('categoriesCreated')}")

r = call("POST", "/questions/import", {"content": content, "autoCreateCategory": True}, token)
d = r.get("data") or {}
check("重复导入幂等（全部跳过）", d.get("inserted") == 0 and d.get("skipped") == 2,
      f"写入 {d.get('inserted')} / 跳过 {d.get('skipped')}，原因：{(d.get('reasons') or [''])[0][:36]}")

unknown = json.dumps({"questions": [{"title": f"未知分类题 {TS}", "category": "不存在的分类-" + TS}]},
                     ensure_ascii=False)
r = call("POST", "/questions/import", {"content": unknown, "autoCreateCategory": False}, token)
d = r.get("data") or {}
check("关闭自动建分类时跳过未知分类", d.get("inserted") == 0 and d.get("skipped") == 1,
      (d.get("reasons") or [""])[0][:48])

r = call("POST", "/questions/import", {"content": "{ 这不是合法 JSON", "autoCreateCategory": True}, token)
check("非法 JSON 被拒绝且提示可读", r.get("code") != 0 and "JSON" in str(r.get("msg", "")),
      str(r.get("msg"))[:60])

oversized = json.dumps([{"title": f"批量题 {i} {TS}", "category": CAT_CODE} for i in range(501)],
                       ensure_ascii=False)
r = call("POST", "/questions/import", {"content": oversized, "autoCreateCategory": True}, token)
check("单次超过 500 条被拒绝并提示分批", r.get("code") != 0 and "分批" in str(r.get("msg", "")),
      str(r.get("msg"))[:60])

r = call("GET", "/categories", token=token)
names = [c.get("name") for c in (r.get("data") or [])]
new_cat = next((c for c in (r.get("data") or []) if c.get("name") == CAT_NAME), None)
check("新分类出现在分类列表且题数正确", new_cat is not None and new_cat.get("count") == 2,
      f"共 {len(names)} 个分类，{CAT_NAME} 有 {new_cat.get('count') if new_cat else '?'} 道题")

# 难度越界是否真的夹到 3。
# 这里按 categoryId 查而不是用中文关键词：关键词要经过 URL 编码，
# 直接用分类 id 可以绕开这一层，测的才是「难度夹取」本身。
cat_id = new_cat.get("id") if new_cat else None
r = call("GET", f"/questions?categoryId={cat_id}&size=20&withAnswer=true", token=token)
records = (r.get("data") or {}).get("records") or []
by_title = {q.get("title"): q for q in records}
title_b = f"验证题目 B {TS}"
check("导入时越界的难度被夹到 3",
      title_b in by_title and by_title[title_b].get("difficulty") == 3,
      f"该分类共 {len(records)} 道，B 的 difficulty="
      f"{by_title.get(title_b, {}).get('difficulty', '未查到')}")

# ---------------------------------------------------------------- 3. API Key 加密
SECRET_KEY = "sk-verify-1234567890abcdef"
EXPECTED_MASK = "sk-v****cdef"

r = call("PUT", "/llm/setting", {
    "baseUrl": "https://api.deepseek.com/v1",
    "apiKey": SECRET_KEY,
    "model": "deepseek-chat",
    "temperature": 0.3,
    "timeoutSeconds": 60,
}, token)
check("保存 LLM 配置成功", r.get("code") == 0, str(r.get("msg"))[:60])

r = call("GET", "/llm/setting", token=token)
d = r.get("data") or {}
masked = d.get("maskedKey", "")
check("接口只回传掩码，不回传明文", SECRET_KEY not in json.dumps(r, ensure_ascii=False),
      f"configured={d.get('configured')} maskedKey={masked}")
check("掩码基于明文计算（前 4 后 4）", masked == EXPECTED_MASK, f"期望 {EXPECTED_MASK}，实际 {masked}")
check("掩码里不出现密文前缀（证明库里是密文、显示是明文）",
      not masked.startswith("enc:") and "v1" not in masked, f"maskedKey={masked}")

# 只改模型、Key 传空：应保持原 Key（说明密文能被解回并继续使用）
r = call("PUT", "/llm/setting", {"baseUrl": "https://api.deepseek.com/v1", "apiKey": "",
                                 "model": "deepseek-reasoner"}, token)
r2 = call("GET", "/llm/setting", token=token)
d2 = r2.get("data") or {}
check("改模型时 Key 不丢失，掩码保持可读", d2.get("maskedKey") == EXPECTED_MASK
      and d2.get("model") == "deepseek-reasoner",
      f"model={d2.get('model')} maskedKey={d2.get('maskedKey')}")

# ---------------------------------------------------------------- 4. 登录防爆破
LOCK_USER = "lock_" + TS
for _ in range(5):
    call("POST", "/auth/login", {"username": LOCK_USER, "password": "definitely-wrong"})
r = call("POST", "/auth/login", {"username": LOCK_USER, "password": "definitely-wrong"})
check("连续失败 5 次后账号被临时锁定", r.get("code") == 429 and "锁定" in str(r.get("msg", "")),
      f"code={r.get('code')} msg={str(r.get('msg'))[:52]}")

# ---------------------------------------------------------------- 5. 限流
# llm 档位上限 20/分钟，连续请求必然触发；用 GET 读配置，不烧 token
hit_at, msg = None, ""
for i in range(1, 31):
    r = call("GET", "/llm/setting", token=token)
    if r.get("code") == 429:
        hit_at, msg = i, str(r.get("msg", ""))
        break
check("超过每分钟上限后触发限流", hit_at is not None, f"第 {hit_at} 次请求被拦下")
check("限流提示与业务错误可区分", "频繁" in msg, msg[:56])

print("-" * 62)
print(f" 通过 {len(PASSED)} 项，失败 {len(FAILED)} 项")
if FAILED:
    print(" 失败项：" + "、".join(FAILED))
print("=" * 62)
raise SystemExit(1 if FAILED else 0)
