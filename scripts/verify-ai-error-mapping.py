"""验证 DeepSeekService 的错误映射分支 —— 这些分支正常调用走不到，只能靠故障注入。

覆盖 7 个分支：401（Key 无效）/ 402（余额不足）/ 429（频率限制）/ 500（default）
/ 空内容 / 连接被拒 / 截断告警（finish_reason=length）。

为什么需要这个脚本：真实 API 只会返回 200，上面这些分支在正常验收里**永远走不到**，
但它们的文案会直接展示给玩家，写错了没人会发现。

前置（两步）：
    1. 另开一个终端起故障桩：
           node scripts/deepseek-fault-stub.mjs
    2. 后端指向桩（Key 填任意非空值，桩不校验）：
           OVERRIDE_BASE_URL=http://127.0.0.1:9099 OVERRIDE_API_KEY=stub-local \
               python scripts/start-backend-with-key.py
       注意：跑完必须把后端切回真实 API，否则会留下一台指向桩的机器。

用法：
    python scripts/verify-ai-error-mapping.py

输出：`.runtime/error-mapping-report.json`
另：截断告警是 log.warn 而非响应字段，需另行 grep 后端日志确认：
    grep "截断" .runtime/backend.log
"""
import json
import os
import time
import urllib.error
import urllib.request

BASE = os.environ.get("MINDTRACE_API", "http://127.0.0.1:8080/api")
MODE_FILE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                         ".runtime", "stub-mode.txt")
REPORT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                      ".runtime", "error-mapping-report.json")

# 模式 -> 期望的 aiNotice（None 表示期望走成功路径、没有降级提示）
EXPECTED = {
    "error401": "DeepSeek API Key 无效或已过期",
    "error402": "DeepSeek 账户余额不足",
    "error429": "DeepSeek API 请求过于频繁，请稍后重试",
    "error500": "DeepSeek 请求失败，HTTP 500",
    "empty": "DeepSeek 返回了空内容",
    "refused": "无法连接 DeepSeek API，请检查网络或稍后重试",
    "truncate": None,
    "normal": None,
}

results = []


def set_mode(mode):
    with open(MODE_FILE, "w", encoding="utf-8") as handle:
        handle.write(mode + "\n")
    time.sleep(0.3)


def call(method, path, body=None, token=None, timeout=120):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8")), resp.status
    except urllib.error.HTTPError as exc:
        return {"__error__": exc.read().decode("utf-8", "replace")}, exc.code


name = "errmap" + str(int(time.time() * 1000))
reg, _ = call("POST", "/auth/register",
              {"username": name, "nickname": "错误映射", "password": "errmap123456"})
token = reg["data"]["token"]
print(f"账号 {name}", flush=True)

# 前置自检：后端必须指向故障桩。否则所有模式都会拿到真实的 200 响应，
# 8 条断言全部 FAIL，却看不出原因 —— 那样排查会白费很久。
set_mode("error401")
probe, _ = call("POST", "/cases/1/chat", {"npcId": 1, "message": "前置自检"}, token=token)
if (probe.get("data") or {}).get("aiAvailable") is True:
    set_mode("normal")
    print("FAIL: 后端没有指向故障桩 —— 它当前连的是真实 API。")
    print("      请先起桩并把后端指过去（见脚本头部「前置」说明），再重跑本脚本。")
    raise SystemExit(2)
print("前置自检通过：后端已指向故障桩。", flush=True)

for mode, expected_notice in EXPECTED.items():
    set_mode(mode)
    resp, code = call("POST", "/cases/1/chat",
                      {"npcId": 1, "message": f"测试模式 {mode}"}, token=token)
    data = resp.get("data") or {}
    notice = data.get("aiNotice")
    reply = data.get("reply") or ""

    if expected_notice is None:
        ok = code == 200 and data.get("aiAvailable") is True and notice is None and reply.strip() != ""
    else:
        ok = code == 200 and data.get("aiAvailable") is False and notice == expected_notice

    entry = {
        "mode": mode,
        "http": code,
        "httpIs200": code == 200,
        "aiAvailable": data.get("aiAvailable"),
        "aiNotice": notice,
        "expectedNotice": expected_notice,
        "replyHead": reply[:60],
        "passed": ok,
    }
    results.append(entry)
    print(f"[{mode}] {'PASS' if ok else 'FAIL'}  http={code} "
          f"aiAvailable={data.get('aiAvailable')} notice={notice!r}", flush=True)

set_mode("normal")
failed = [r for r in results if not r["passed"]]

# 自检：声明的模式必须全部被执行过。若 EXPECTED 与 results 对不上，
# 说明有人改了 EXPECTED 却让某条被跳过 —— 那种「看起来全绿、其实少跑了一条」
# 的假绿最危险，必须显式拦住。
declared = len(EXPECTED)
executed = len(results)
skipped = declared - executed

with open(REPORT, "w", encoding="utf-8") as handle:
    json.dump({"username": name, "results": results,
               "total": executed, "declared": declared, "skipped": skipped,
               "failed": len(failed)}, handle,
              ensure_ascii=False, indent=2)
print(f"\nSUMMARY declared={declared} executed={executed} "
      f"skipped={skipped} failed={len(failed)}", flush=True)
print("报告已写入", REPORT, flush=True)

# 退出码：有 FAIL 或少跑了模式都必须非零，否则 CI 会把假绿当通过。
if skipped != 0 or failed:
    raise SystemExit(1)
