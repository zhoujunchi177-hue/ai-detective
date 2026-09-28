"""推理审阅 / 结案评估两条链路的 token 计量。

为什么单独有它 —— 这两条链路和 NPC 对话的成本结构**完全不同**：
  - NPC 对话：`system + 注入上下文` 是**稳定前缀**，几乎每轮命中缓存（命中价 0.02）；
  - 这两条链路：**单次调用**（一局里玩家只提交几次），而且**整条消息就是一次性的**
    （假说/结论直接拼在同一条 user 消息里），第一次调用基本整段按**未命中价（1.0）**计费。
    同样一个字符，在这里贵 50 倍。

所以要判断「还有没有值得压的 token」，必须先看清这两条链路的规模 ——
在错的地方优化，等于白花时间。

前置（与 measure-npc-tokens.py 相同，两步缺一不可）：
    node scripts/deepseek-meter-proxy.mjs
    OVERRIDE_BASE_URL=http://127.0.0.1:9098 python scripts/start-backend-with-key.py
    ⚠️ 只覆盖 base-url、不覆盖 api-key —— 否则代理拿不到真实 Key，上游 401，就没有 usage 了。
    ⚠️ 跑完记得切回：python scripts/start-backend-with-key.py

**不动 demo 账号的数据**：注册全新账号，自己把地点调查一遍把线索刷出来，
再在**新账号**上跑推理与结案 —— 既拿到接近真实的案件上下文规模，又不污染演示数据。

用法：
    python scripts/measure-reasoning-tokens.py
    python scripts/measure-reasoning-tokens.py --hypotheses 3 --case 1

输出：`.runtime/token-measure-reasoning.json`，并在终端打印按链路汇总的归因表。
退出码：0 成功 / 2 前置不满足（后端没指向代理）
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METER_JSONL = os.path.join(REPO, ".runtime", "token-meter.jsonl")
METER_PORT = int(os.environ.get("METER_PORT", "9098"))
BASE = os.environ.get("MINDTRACE_API", "http://127.0.0.1:8080/api")

# 固定假说：改前改后必须用同一组，否则数字不可比。刻意给了不同长度。
HYPOTHESES = [
    "我认为作案者熟悉酒店供水系统，利用屋顶水箱检修的窗口实施犯罪，"
    "并通过制造供水异常来掩盖声响。值班表上缺失的十五分钟是关键。",
    "我怀疑死者是自行进入水箱区域的，没有他杀迹象，供水异常只是设备老化。",
    "我觉得前台在隐瞒当晚的访客登记记录，凶手可能是外来访客，"
    "而且电梯门长时间开启也说明有人在外围接应。",
]

SUBMISSION = {
    "hypothesis": HYPOTHESES[0],
    "keyPeople": "值班工程师、前台主管",
    "keyTimeline": "20:10 住客投诉供水异常；20:40 屋顶水箱发现遗体；21:00 封锁现场。",
    "evidenceClueIds": [],
    "reasoningText": "供水异常时段覆盖遗体发现之前的窗口，说明有人刻意让水泵空转以掩盖声响。",
    "conclusion": "作案者是熟悉酒店供水系统的内部人员，利用检修窗口实施并伪装成设备意外。",
}


def call(method, path, body=None, token=None, timeout=240):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8")), resp.status
    except urllib.error.HTTPError as exc:
        return {"__http_error__": exc.code, "body": exc.read().decode("utf-8", "replace")}, exc.code


def preflight():
    """后端必须指向计量代理，否则 usage 根本不会被记录，最后只会得到一份「全是 0」的报告。"""
    try:
        probe = urllib.request.Request(
            f"http://127.0.0.1:{METER_PORT}/__probe__", method="POST",
            data=b"{}", headers={"Content-Type": "application/json"})
        urllib.request.urlopen(probe, timeout=5)
    except urllib.error.HTTPError:
        pass  # 代理回了 4xx/5xx 也算「在监听」，能连上就行
    except Exception as exc:
        print(f"FAIL: 计量代理 {METER_PORT} 连不上（{exc}）。先启动 "
              f"`node scripts/deepseek-meter-proxy.mjs` 并让后端指向它。", flush=True)
        return False
    health, _ = call("GET", "/health")
    if (health.get("data") or {}).get("deepSeekConfigured") is not True:
        print("FAIL: /api/health 的 deepSeekConfigured 不是 true。", flush=True)
        return False
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", type=int, default=1)
    parser.add_argument("--hypotheses", type=int, default=3)
    args = parser.parse_args()

    if not preflight():
        return 2

    for path in (METER_JSONL, os.path.join(REPO, ".runtime", "token-meter-summary.json")):
        if os.path.exists(path):
            os.remove(path)

    name = "reasonm" + str(int(time.time() * 1000))[-10:]
    reg, code = call("POST", "/auth/register",
                     {"username": name, "nickname": "推理计量", "password": "reasonm123456"})
    if code != 200:
        print(f"FAIL: 注册失败 {code} {reg}", flush=True)
        return 2
    token = reg["data"]["token"]
    print(f"账号 {name}（全新账号，不动 demo 数据）\n", flush=True)

    # 先自己把地点调查一遍，让案件上下文接近真实规模
    detail, _ = call("GET", f"/cases/{args.case}", token=token)
    locations = (detail.get("data") or {}).get("locations") or []
    print(f"调查地点（共 {len(locations)} 个）：")
    for loc in locations:
        key = loc.get("locationKey") or loc.get("key") or loc.get("id")
        resp, code = call("POST", f"/cases/{args.case}/investigate",
                          {"locationKey": key}, token=token)
        print(f"  {str(key):<26} http={code}")
    progress = ((call("GET", f"/cases/{args.case}", token=token)[0].get("data") or {})
                .get("progress") or {})
    print(f"  → 已发现线索 {progress.get('discoveredClues')}/{progress.get('totalClues')}，"
          f"已调查地点 {progress.get('investigatedLocations')}/{progress.get('totalLocations')}\n")

    hypotheses = HYPOTHESES[: args.hypotheses]
    print(f"推理审阅（{len(hypotheses)} 次，JSON 模式）：")
    for i, hyp in enumerate(hypotheses, start=1):
        resp, code = call("POST", f"/cases/{args.case}/reasoning",
                          {"hypothesis": hyp}, token=token)
        data = resp.get("data") or {}
        print(f"  [{i}] http={code} aiAvailable={data.get('aiAvailable')} "
              f"confidence={data.get('confidence')} 假说={len(hyp)}字")

    print("\n结案评估（1 次，JSON 模式）：")
    resp, code = call("POST", f"/cases/{args.case}/submit", dict(SUBMISSION), token=token)
    data = resp.get("data") or {}
    print(f"  http={code} aiAvailable={data.get('aiAvailable')} "
          f"aiReport={'有' if data.get('aiReport') else '无'}")

    time.sleep(1)
    rows = []
    if os.path.exists(METER_JSONL):
        with open(METER_JSONL, encoding="utf-8") as handle:
            rows = [json.loads(line) for line in handle if line.strip()]
    rows = [r for r in rows if r.get("usage")]
    if not rows:
        print("\nFAIL: 没有拿到任何 usage 记录。后端是不是没指向计量代理？", flush=True)
        return 2

    print("\n" + "=" * 78)
    print(f"归因（{len(rows)} 次真实调用）")
    print("=" * 78)
    print(f"{'#':>3} {'链路':>6} {'prompt':>7} {'hit':>6} {'miss':>6} {'输出':>6} "
          f"{'思考':>5} {'maxTok':>7}")
    print("-" * 78)
    for i, row in enumerate(rows, start=1):
        usage = row["usage"]
        print(f"{i:>3} {str(row.get('kind')):>6} {usage['prompt']:>7} "
              f"{str(usage.get('cacheHit')):>6} {str(usage.get('cacheMiss')):>6} "
              f"{usage['completion']:>6} {str(usage.get('reasoning')):>5} "
              f"{str(row.get('maxTokens')):>7}")

    by_kind = {}
    for row in rows:
        usage = row["usage"]
        agg = by_kind.setdefault(row.get("kind") or "other",
                                 {"calls": 0, "prompt": 0, "completion": 0,
                                  "hit": 0, "miss": 0, "reasoning": 0})
        agg["calls"] += 1
        agg["prompt"] += usage["prompt"]
        agg["completion"] += usage["completion"]
        agg["hit"] += usage.get("cacheHit") or 0
        agg["miss"] += usage.get("cacheMiss") or 0
        agg["reasoning"] += usage.get("reasoning") or 0

    print("-" * 78)
    print("单价（deepseek-flash 空闲档，元/百万）：命中 0.02 / 未命中 1.0 / 输出 4.0")
    print(f"{'链路':>8} {'次数':>5} {'prompt':>8} {'未命中占比':>10} {'输出':>6} "
          f"{'思考':>5} {'费用(相对)':>11}")
    for kind, agg in sorted(by_kind.items()):
        cost = agg["hit"] * 0.02 + agg["miss"] * 1.0 + agg["completion"] * 4.0
        miss_share = agg["miss"] / agg["prompt"] * 100 if agg["prompt"] else 0
        print(f"{kind:>8} {agg['calls']:>5} {agg['prompt']:>8} {miss_share:>9.0f}% "
              f"{agg['completion']:>6} {agg['reasoning']:>5} {cost:>11.1f}")

    out = os.path.join(REPO, ".runtime", "token-measure-reasoning.json")
    with open(out, "w", encoding="utf-8") as handle:
        json.dump({"byKind": by_kind, "rows": [
            {"kind": r.get("kind"), "usage": r["usage"], "maxTokens": r.get("maxTokens"),
             "jsonMode": r.get("jsonMode"), "breakdown": r.get("breakdown")} for r in rows]},
            handle, ensure_ascii=False, indent=2)
    print(f"\n报告 → {out}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
