"""
异步简历分析链路验证：不依赖可用的 LLM Key。
把 Base URL 指向一个没人监听的端口，验证
  上传非阻塞 → 状态流转 → 失败原因落库 → 轮询拿到终态 → 结束后可再次提交。
"""
import json
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8081/api"
OP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with OP.open(req, timeout=60) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"code": e.code, "msg": e.read().decode("utf-8", "replace")[:300], "data": None}


def upload(path, filename, content, token):
    b = "----async" + str(int(time.time() * 1000))
    body = (
        f"--{b}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode() + content + f"\r\n--{b}--\r\n".encode()
    req = urllib.request.Request(BASE + path, data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={b}")
    req.add_header("Authorization", "Bearer " + token)
    try:
        with OP.open(req, timeout=60) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"code": e.code, "msg": e.read().decode("utf-8", "replace")[:300], "data": None}


user = "async_" + time.strftime("%m%d_%H%M%S")
call("POST", "/auth/register", {"username": user, "password": "zhang123456", "nickname": "异步验证"})
token = call("POST", "/auth/login", {"username": user, "password": "zhang123456"})["data"]["token"]
print(f"测试账号 {user}")

r = call("PUT", "/llm/setting", {
    "baseUrl": "http://127.0.0.1:9/v1",   # 没人监听的端口，连接会被立刻拒绝
    "apiKey": "sk-fake-for-async-check",
    "model": "fake-model",
    "timeoutSeconds": 20,
}, token=token)
assert r.get("code") == 0, f"写入 LLM 配置失败：{r}"
print("LLM 配置指向不可达地址 http://127.0.0.1:9/v1")

resume = "张三 武汉轻工大学 软件工程 2028届 熟悉 Java SpringBoot MySQL Redis Docker".encode("utf-8")

# [1] 上传必须立刻返回，不能阻塞在模型调用上
t0 = time.time()
r = upload("/resume/analyze", "resume.txt", resume, token)
upload_ms = (time.time() - t0) * 1000
assert r.get("code") == 0, f"上传失败：{r}"
job_id = r["data"]["id"]
print(f"\n[1] 上传耗时 {upload_ms:.0f} ms，返回 id={job_id} status={r['data']['status']}")
assert upload_ms < 3000, f"上传耗时 {upload_ms:.0f} ms，说明请求仍在同步等模型"
assert r["data"]["status"] == "PENDING"
print("    -> 非阻塞确认：没有等模型，直接拿到任务 id")

# [2] 轮询拿到终态，且失败原因必须落库
t0 = time.time()
seen = []
final = None
while time.time() - t0 < 60:
    d = call("GET", f"/resume/{job_id}", token=token).get("data") or {}
    st = d.get("status")
    if st and (not seen or seen[-1] != st):
        seen.append(st)
        print(f"[2] 状态变为 {st}（提交后 {time.time() - t0:.1f}s）")
    if st in ("SUCCESS", "FAILED"):
        final = d
        break
    time.sleep(0.5)

assert final is not None, "60 秒内没拿到终态，轮询链路有问题"
assert final["status"] == "FAILED", f"预期 FAILED，实际 {final['status']}"
assert final.get("errorMsg"), "失败原因没落库，前端会一直转圈"
print(f"    状态流转：{' -> '.join(seen)}")
print(f"    失败原因已落库：{final['errorMsg'][:100]}")

# [3] 终态之后应当允许再次提交
r2 = upload("/resume/analyze", "resume.txt", resume, token)
assert r2.get("code") == 0, f"上一个任务已结束却仍被拒绝：{r2}"
print(f"\n[3] 上一个任务结束后可再次提交 -> 新任务 id={r2['data']['id']}")

print(f"\nUSER_FOR_NEXT_PHASE={user}")
print("阶段一通过：上传非阻塞、状态流转、失败落库、终态后可重提。")
