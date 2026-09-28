"""发布前验收 —— Part 22：基础性能与响应规模。

覆盖：
  1. 关键只读接口的响应时间（p50 / p95），不含 AI 接口（AI 耗时由真实调用决定）
  2. 列表接口的返回规模是否有上界（不会一次性吐出全表）
  3. 调查日志分页：size 上限被钳制、翻页稳定、total 自洽
  4. 案件详情不会因为线索变多而线性变慢（无 N+1 的粗筛）
  5. AI 接口不占着数据库连接（并发打 3 个推理请求后，普通读接口仍然秒回）
  6. 大响应体不超预算（单次 JSON 响应 < 2 MB）

判定口径（刻意宽松，只抓「明显退化」而不是追求数字好看）：
  - 只读接口 p95 > 1500ms  → FAIL（本机 MySQL，正常应在百毫秒级）
  - 单次响应体 > 2MB       → FAIL
  - 分页 size 未钳制        → FAIL

用法：
    python scripts/verify-rc-performance.py

环境变量：
    MINDTRACE_API   后端地址，默认 http://127.0.0.1:8080/api
    MINDTRACE_USER  复用已有账号（默认临时注册一个新账号）

输出：`.runtime/performance-report.json`
"""
import json
import os
import re
import statistics
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("MINDTRACE_API", "http://127.0.0.1:8080/api")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPORT = os.path.join(ROOT, ".runtime", "performance-report.json")

READ_P95_BUDGET_MS = 1500
MAX_BODY_BYTES = 2 * 1024 * 1024

passed = 0
failed = 0
warned = 0
results = []


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        results.append({"name": name, "ok": True, "detail": detail})
        print("[PASS] " + name + (("  " + str(detail)) if detail else ""))
    else:
        failed += 1
        results.append({"name": name, "ok": False, "detail": detail})
        print("[FAIL] " + name + (("  " + str(detail)) if detail else ""))


def warn(name, detail=""):
    global warned
    warned += 1
    results.append({"name": name, "ok": None, "detail": detail})
    print("[WARN] " + name + (("  " + str(detail)) if detail else ""))


def call(method, path, body=None, token=None, timeout=60):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            code = resp.status
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        code = exc.code
    except Exception as exc:  # noqa: BLE001
        return 0, {"error": str(exc)}, time.time() - started, 0
    elapsed = time.time() - started
    try:
        return code, json.loads(raw.decode("utf-8")), elapsed, len(raw)
    except Exception:  # noqa: BLE001
        return code, {}, elapsed, len(raw)


# ---------------------------------------------------------------- 准备账号
code, health, _, _ = call("GET", "/health")
check("后端健康检查可达", code == 200 and health.get("data", {}).get("status") == "UP",
      "status=" + str(health.get("data", {}).get("status")))

username = os.environ.get("MINDTRACE_USER") or ("perf_" + uuid.uuid4().hex[:8])
password = os.environ.get("MINDTRACE_PASS") or "Perf#2026"
code, body, _, _ = call("POST", "/auth/register",
                        {"username": username, "password": password, "nickname": "性能探针"})
if code == 200 and body.get("data", {}).get("token"):
    token = body["data"]["token"]
else:
    code, body, _, _ = call("POST", "/auth/login", {"username": username, "password": password})
    token = body.get("data", {}).get("token")
check("拿到可用令牌（注册或登录）", bool(token), "http=" + str(code))
if not token:
    print("无法取得令牌，后续检查无法执行")
    raise SystemExit(1)

# ------------------------------------------------- 1. 只读接口响应时间
READ_TARGETS = [
    ("案件列表", "GET", "/cases", None),
    ("案件详情", "GET", "/cases/1", None),
    ("调查地图", "GET", "/cases/1/locations", None),
    ("线索列表", "GET", "/cases/1/clues", None),
    ("排行榜", "GET", "/ranking", None),
    ("个人档案", "GET", "/user/profile", None),
    ("调查日志首页", "GET", "/cases/1/history?page=1&size=20", None),
]
ROUNDS = 5
timings = {}
for label, method, path, payload in READ_TARGETS:
    samples = []
    sizes = []
    last_code = 0
    for _ in range(ROUNDS):
        code, _body, secs, size = call(method, path, payload, token=token)
        last_code = code
        samples.append(secs * 1000)
        sizes.append(size)
    p50 = statistics.median(samples)
    p95 = sorted(samples)[max(0, int(len(samples) * 0.95) - 1)]
    timings[label] = {"p50": round(p50, 1), "p95": round(p95, 1), "http": last_code,
                      "maxBytes": max(sizes)}
    check(label + " 返回 200", last_code == 200, "http=" + str(last_code))
    check(label + " p95 在预算内", p95 <= READ_P95_BUDGET_MS,
          "p50=" + str(round(p50, 1)) + "ms p95=" + str(round(p95, 1)) + "ms")
    check(label + " 响应体不超 2MB", max(sizes) <= MAX_BODY_BYTES,
          str(max(sizes)) + " bytes")

# ------------------------------------------------- 2. 列表规模有上界
code, body, _, size = call("GET", "/cases", token=token)
cases = body.get("data") or []
check("案件列表是数组", isinstance(cases, list), "type=" + type(cases).__name__)
check("案件列表规模可控（<=100）", len(cases) <= 100, "n=" + str(len(cases)))

code, body, _, _ = call("GET", "/ranking", token=token)
ranking = body.get("data") or []
if isinstance(ranking, dict):
    ranking = ranking.get("entries") or ranking.get("list") or []
check("排行榜规模可控（<=200）", len(ranking) <= 200, "n=" + str(len(ranking)))

# ------------------------------------------------- 3. 日志分页
code, body, _, _ = call("GET", "/cases/1/history?page=1&size=20", token=token)
data = body.get("data") or {}
check("日志返回分页对象", code == 200 and "entries" in data,
      "keys=" + ",".join(sorted(data.keys())[:6]))
page_size = data.get("size")
check("日志 size 与请求一致（20）", page_size == 20, "size=" + str(page_size))

# size 上限钳制：请求一个离谱的值
code, body, _, size = call("GET", "/cases/1/history?page=1&size=99999", token=token)
big = body.get("data") or {}
check("日志 size 上限被钳制（<=200）", isinstance(big.get("size"), int) and big["size"] <= 200,
      "size=" + str(big.get("size")) + " bytes=" + str(size))

# 负页码 / 0 页
code, body, _, _ = call("GET", "/cases/1/history?page=0&size=20", token=token)
check("页码 0 不产生 5xx", code < 500, "http=" + str(code))
code, body, _, _ = call("GET", "/cases/1/history?page=-3&size=20", token=token)
check("负页码不产生 5xx", code < 500, "http=" + str(code))

# 翻页稳定性：第 1 页与第 2 页不应重复
code1, b1, _, _ = call("GET", "/cases/1/history?page=1&size=20", token=token)
code2, b2, _, _ = call("GET", "/cases/1/history?page=2&size=20", token=token)
e1 = (b1.get("data") or {}).get("entries") or []
e2 = (b2.get("data") or {}).get("entries") or []
if e1 and e2:
    ids1 = {json.dumps(e, sort_keys=True, ensure_ascii=False) for e in e1}
    ids2 = {json.dumps(e, sort_keys=True, ensure_ascii=False) for e in e2}
    check("第 1/2 页无重复条目", len(ids1 & ids2) == 0, "overlap=" + str(len(ids1 & ids2)))
else:
    warn("第 1/2 页无重复条目（样本不足，已跳过对比）",
         "page1=" + str(len(e1)) + " page2=" + str(len(e2)))

total = (b1.get("data") or {}).get("total")
check("total 是数字且 >= 首页条目数",
      isinstance(total, int) and total >= len(e1),
      "total=" + str(total) + " page1=" + str(len(e1)))

# ------------------------------------------------- 4. 详情接口不随线索数线性变慢
# 先解锁两个地点，再看详情耗时是否劣化（粗略判断有无 N+1）
call("POST", "/cases/1/investigate", {"locationId": "lobby"}, token=token)
before = timings.get("案件详情", {}).get("p95", 0)
samples = []
for _ in range(3):
    _code, _b, secs, _s = call("GET", "/cases/1", token=token)
    samples.append(secs * 1000)
after = statistics.median(samples)
check("解锁线索后案件详情未明显劣化（< 3x 或 < 300ms）",
      after < max(before * 3, 300),
      "before_p95=" + str(before) + "ms after_p50=" + str(round(after, 1)) + "ms")

# ------------------------------------------------- 5. AI 接口不长时间占用连接池
# 并发发起 3 个推理请求（真实 AI 可能各需数秒），期间普通读接口必须仍然秒回。
# 这里不等待推理结果，只看「读接口是否被拖慢」。
import threading  # noqa: E402

def fire_reasoning(idx):
    call("POST", "/cases/1/reasoning",
         {"hypothesis": "并发性能探针 %d：供水异常与遗体发现时间是否吻合。" % idx},
         token=token, timeout=300)


threads = [threading.Thread(target=fire_reasoning, args=(i,), daemon=True) for i in range(3)]
for t in threads:
    t.start()

probe_latency = []
for _ in range(6):
    _code, _b, secs, _s = call("GET", "/cases/1/clues", token=token)
    probe_latency.append(secs * 1000)
    time.sleep(0.4)

worst = max(probe_latency) if probe_latency else 0
check("AI 推理进行中，只读接口仍然快速响应（< 1000ms）", worst < 1000,
      "worst=" + str(round(worst, 1)) + "ms samples=" +
      ",".join(str(round(x, 1)) for x in probe_latency))

for t in threads:
    t.join(timeout=1)

# ------------------------------------------------- 6. 汇总
report = {
    "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
    "base": BASE,
    "account": username,
    "readP95BudgetMs": READ_P95_BUDGET_MS,
    "timings": timings,
    "readLatencyDuringAI": [round(x, 1) for x in probe_latency],
    "passed": passed,
    "failed": failed,
    "warned": warned,
    "results": results,
}
os.makedirs(os.path.dirname(REPORT), exist_ok=True)
with open(REPORT, "w", encoding="utf-8") as fh:
    json.dump(report, fh, ensure_ascii=False, indent=2)

# 自检：源码里声明的 check(...) 是否都真的执行过。
#
# 这里**不能**用「调用点个数 == 执行次数」来比对：本脚本的检查跑在
# 循环里（7 个接口 × 5 轮），执行次数必然远大于调用点个数，一比就误报。
# 改为按**名字**比对 —— 源码里出现的每个字面量名字都必须至少被「交代」过一次。
#
# 「交代」= 执行过（PASS/FAIL）**或**显式跳过（WARN）。
# 允许 WARN 顶替，是因为有些检查在数据不足时本就该跳过（例如日志为空时
# 无从比较两页是否重复）。但跳过**必须留痕**：跳过分支的 WARN 名字要以
# 该检查名开头，否则自检会报「从未执行」。
# 真正要防的是「一条断言凭空消失、日志里既没 PASS 也没 FAIL」——
# .ps1 脚本被 GBK 解码吃掉换行时就出过这个事故。
#
# 动态拼出来的名字（如 label + " 返回 200"）匹配不到，自然不参与自检。
with open(os.path.abspath(__file__), encoding="utf-8") as fh:
    source = fh.read()
declared_names = set(re.findall(r'check\(\s*"([^"]+)"', source))
executed_names = {r["name"] for r in results if r["ok"] is not None}
warned_names = {r["name"] for r in results if r["ok"] is None}
accounted = executed_names | warned_names
missing = sorted(n for n in declared_names if not any(a.startswith(n) for a in accounted))

print("")
print("SUMMARY declared=%d executed=%d warned=%d passed=%d failed=%d"
      % (len(declared_names), len(executed_names), len(warned_names), passed, failed))
if missing:
    print("[FAIL] 以下声明过的检查项既没执行、也没留下跳过记录：")
    for name in missing:
        print("  - " + name)
    failed += 1
print("报告：" + os.path.relpath(REPORT, ROOT))
raise SystemExit(1 if failed else 0)
