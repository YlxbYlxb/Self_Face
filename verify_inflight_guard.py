"""
并发守卫验证：数据库中已存在一条该用户的「进行中」任务时，上传必须被拒绝。
运行前请先用 SQL 插入一条 status=RUNNING 的记录（见配套命令）。
用法：python verify_inflight_guard.py <username>
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8081/api"
OP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
username = sys.argv[1]


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
    b = "----guard" + str(int(time.time() * 1000))
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


token = call("POST", "/auth/login", {"username": username, "password": "zhang123456"})["data"]["token"]
before = len(call("GET", "/resume/list", token=token).get("data") or [])

r = upload("/resume/analyze", "resume.txt", b"zhang san java springboot", token)
after = len(call("GET", "/resume/list", token=token).get("data") or [])

blocked = r.get("code") != 0 and "进行中" in str(r.get("msg", ""))
print(f"已存在进行中的任务时上传简历：{'已被拦住' if blocked else '没拦住！'}")
print(f"  服务端返回：{r.get('msg')}")
print(f"  任务记录数 {before} -> {after}（应当不变，说明没有新建任务）")
assert blocked, "并发守卫失效：同一用户会同时跑多个分析，重复烧 token"
assert after == before, "被拒绝的请求不应该留下新任务记录"
print("并发守卫验证通过。")
