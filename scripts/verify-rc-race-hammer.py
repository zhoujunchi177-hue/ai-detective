"""MindTrace 并发竞态压力验证（不调用 AI，可反复跑）。

背景：线索解锁 / 谜题提交 / 证据关联都是「先查后写」，并发下会撞唯一键；
把去重改成 INSERT IGNORE 之后又出现了 MySQL 死锁。修复方案是先用
`SELECT ... FOR UPDATE` 锁住用户行，把同一玩家的并发写串行化。

单跑一轮可能是运气好。所以这个脚本把四个并发场景各重复 N 轮（默认 8 轮），
只要有一轮出现 5xx 或不符合预期的状态码就判失败。不涉及 AI，几秒就能跑完。

用法：
    python scripts/verify-rc-race-hammer.py            # 默认 8 轮
    ROUNDS=20 python scripts/verify-rc-race-hammer.py
"""

import json
import os
import random
import re
import string
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.environ.get("MINDTRACE_API", "http://127.0.0.1:8080")
ROUNDS = int(os.environ.get("ROUNDS", "8"))
CASE = 1

results = []
executed = 0
ALPHABET = string.ascii_lowercase + string.digits


def rand(n):
    return "".join(random.choice(ALPHABET) for _ in range(n))


def call(method, path, body=None, token=None, timeout=90):
    url = BASE + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        try:
            return exc.code, json.loads(raw)
        except Exception:
            return exc.code, {"_raw": raw}
    except Exception as exc:
        return 0, {"_error": repr(exc)}


def check(name, ok, detail=""):
    global executed
    executed += 1
    results.append({"name": name, "pass": bool(ok), "detail": detail})
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"   —— {detail}" if detail else ""), flush=True)


def new_user(nick="竞态压测"):
    name = "rcH" + rand(10)
    st, body = call("POST", "/api/auth/register",
                    {"username": name, "nickname": nick, "password": "pass123456"})
    return name, (body.get("data") or {}).get("token")


def parallel(fn, n):
    with ThreadPoolExecutor(max_workers=n) as pool:
        return [f.result() for f in [pool.submit(fn) for _ in range(n)]]


def parallel_calls(thunks):
    with ThreadPoolExecutor(max_workers=len(thunks)) as pool:
        futures = [pool.submit(thunk) for thunk in thunks]
        return [f.result() for f in futures]


print(f"BASE={BASE}  ROUNDS={ROUNDS}", flush=True)

# --- 场景 A：并发调查两个公开地点 -------------------------------------------
a_bad = []
for r in range(ROUNDS):
    _, token = new_user()
    codes = [c for c, _ in parallel_calls([
        (lambda k=k: call("POST", f"/api/cases/{CASE}/investigate", {"locationKey": k}, token))
        for k in ["lobby", "elevator"]
    ])]
    if any(c != 200 for c in codes):
        a_bad.append((r + 1, codes))
check("A 并发调查两个公开地点：每轮都成功", a_bad == [],
      f"{ROUNDS} 轮全通过" if not a_bad else f"异常轮次={a_bad[:4]}")

# --- 场景 B：同一地点并发调查 5 次 ------------------------------------------
b_bad = []
for r in range(ROUNDS):
    _, token = new_user()
    codes = [c for c, _ in parallel(
        lambda: call("POST", f"/api/cases/{CASE}/investigate", {"locationKey": "lobby"}, token), 5)]
    if any(c >= 500 for c in codes):
        b_bad.append((r + 1, codes))
check("B 同一地点并发调查 5 次：无 5xx", b_bad == [],
      f"{ROUNDS} 轮全通过" if not b_bad else f"异常轮次={b_bad[:4]}")

# --- 场景 C：并发提交同一谜题 ----------------------------------------------
c_bad = []
for r in range(ROUNDS):
    _, token = new_user()
    call("POST", f"/api/cases/{CASE}/investigate", {"locationKey": "lobby"}, token)
    st, pz = call("GET", f"/api/cases/{CASE}/puzzles", token=token)
    puzzles = pz.get("data") if isinstance(pz.get("data"), list) else []
    if not puzzles:
        c_bad.append((r + 1, "no puzzle"))
        continue
    pid = puzzles[0]["id"]
    codes = [c for c, _ in parallel(
        lambda: call("POST", f"/api/cases/{CASE}/puzzles/{pid}",
                     {"answer": "一个肯定不对的答案"}, token), 3)]
    if any(c != 200 for c in codes):
        c_bad.append((r + 1, codes))
check("C 并发提交同一谜题：每轮都成功", c_bad == [],
      f"{ROUNDS} 轮全通过" if not c_bad else f"异常轮次={c_bad[:4]}")

# --- 场景 D：并发建立同一条证据关联 -----------------------------------------
d_bad = []
for r in range(ROUNDS):
    _, token = new_user()
    for k in ["lobby", "elevator", "water-system"]:
        call("POST", f"/api/cases/{CASE}/investigate", {"locationKey": k}, token)
    st, cl = call("GET", f"/api/cases/{CASE}/clues", token=token)
    clues = cl.get("data") if isinstance(cl.get("data"), list) else []
    if len(clues) < 2:
        d_bad.append((r + 1, f"only {len(clues)} clues"))
        continue
    body = {"fromClueId": clues[0]["id"], "toClueId": clues[1]["id"],
            "relationType": "SUPPORTS", "note": "压测"}
    results_d = parallel_calls([
        (lambda: call("POST", f"/api/cases/{CASE}/evidence-links", dict(body), token))
        for _ in range(3)
    ])
    codes = [c for c, _ in results_d]
    ok = sum(1 for c in codes if c == 200)
    # 重复的那些必须走到业务提示「已经连过了」，而不是撞唯一键后落进
    # 通用兜底文案 —— 后者说明查重是失效的，只是恰好被数据库挡住了。
    wrong_message = [m.get("message") for c, m in results_d
                     if c != 200 and "已经连过了" not in str(m.get("message"))]
    if ok != 1 or any(c >= 500 for c in codes) or wrong_message:
        d_bad.append((r + 1, codes, wrong_message[:2]))
check("D 并发建同一关联：每轮恰好成功一次，重复方给业务提示且无 5xx", d_bad == [],
      f"{ROUNDS} 轮全通过" if not d_bad else f"异常轮次={d_bad[:3]}")

# --- 场景 E：并发注册同一用户名 ---------------------------------------------
e_bad = []
for r in range(ROUNDS):
    dup = "rcHdup" + rand(6)
    payload = {"username": dup, "nickname": "抢注", "password": "pass123456"}
    codes = [c for c, _ in parallel(lambda: call("POST", "/api/auth/register", dict(payload)), 5)]
    ok = sum(1 for c in codes if c == 200)
    if ok != 1 or any(c >= 500 for c in codes):
        e_bad.append((r + 1, codes))
check("E 并发注册同名：每轮恰好成功一次且无 5xx", e_bad == [],
      f"{ROUNDS} 轮全通过" if not e_bad else f"异常轮次={e_bad[:4]}")

# ---------------------------------------------------------------------------
source = open(os.path.abspath(__file__), encoding="utf-8").read()
declared = len(re.findall(r"^\s*check\(", source, re.M)) - 1
check("源码声明的检查项都已执行", executed >= declared,
      f"declared={declared} executed={executed}")

failed = [r for r in results if not r["pass"]]
report = {"base": BASE, "rounds": ROUNDS, "declared": declared,
          "executed": executed, "failed": len(failed), "results": results}
REPORT = os.path.join(REPO, ".runtime", "rc-race-hammer-report.json")
os.makedirs(os.path.dirname(REPORT), exist_ok=True)
with open(REPORT, "w", encoding="utf-8") as fh:
    json.dump(report, fh, ensure_ascii=False, indent=2)

print(f"\nSUMMARY rounds={ROUNDS} declared={declared} executed={executed} failed={len(failed)}",
      flush=True)
print(f"报告已写入 {REPORT}", flush=True)
if failed:
    for r in failed:
        print(f"  - {r['name']}  ({r['detail']})", flush=True)
    sys.exit(1)
print("全部通过。", flush=True)
