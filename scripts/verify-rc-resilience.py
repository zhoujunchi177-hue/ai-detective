"""MindTrace 发布前全量测试 —— 并发与重复请求（Part 20）。

要回答的问题：**同一个玩家在极短时间内重复触发写操作，会不会被算成多次？**

  * 并发调查同一地点     -> 线索不应重复入库（uk_user_clue 兜底）
  * 并发建立同一条证据关联 -> 只应成功一次（uk_evidence_pair 兜底）
  * 并发发送多条对话     -> 每条用户消息都要落库，不能丢
  * 并发重复提交结案     -> 奖励只能发一次，分数不能被叠加

其中「重复提交结案」用**差分法**验证：两个新账号进度完全相同，
一个只提交一次，另一个并发提交 3 次；两者的 EXP / 金币 / 分数必须相等。
不靠「凭经验猜奖励公式」来判断，避免公式本身变了导致误报。
"""

import json
import os
import random
import re
import string
import sys
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.environ.get("MINDTRACE_API", "http://127.0.0.1:8080")
REPORT = os.path.join(REPO, ".runtime", "rc-resilience-report.json")

results = []
executed = 0
ALPHABET = string.ascii_lowercase + string.digits
CASE = 1
# 三个公开 / 门控地点，按解锁顺序
LOCATIONS = ["lobby", "elevator", "water-system"]


def rand(n):
    return "".join(random.choice(ALPHABET) for _ in range(n))


def call(method, path, body=None, token=None, timeout=240):
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


def section(title):
    print("\n" + "=" * 72 + f"\n{title}\n" + "=" * 72, flush=True)


def new_user(nick="并发测试"):
    name = "rcC" + rand(10)
    st, body = call("POST", "/api/auth/register",
                    {"username": name, "nickname": nick, "password": "pass123456"})
    return name, (body.get("data") or {}).get("token"), st


def profile(token):
    st, body = call("GET", "/api/user/profile", token=token)
    return body.get("data") or {}


def prepare_progress(token):
    """把三个地点都调查一遍，让两个账号的初始条件完全相同。"""
    for key in LOCATIONS:
        call("POST", f"/api/cases/{CASE}/investigate", {"locationKey": key}, token=token)


# ---------------------------------------------------------------------------
section("0. 前置")
st, health = call("GET", "/api/health")
check("后端健康", st == 200 and (health.get("data") or {}).get("status") == "UP", f"http={st}")

# ---------------------------------------------------------------------------
section("1. 并发注册同一用户名：只应成功一次")
dup = "rcRace" + rand(8)
payload = {"username": dup, "nickname": "抢注", "password": "pass123456"}
with ThreadPoolExecutor(max_workers=5) as pool:
    futures = [pool.submit(call, "POST", "/api/auth/register", dict(payload)) for _ in range(5)]
    outcomes = [f.result()[0] for f in futures]
ok_count = sum(1 for s in outcomes if s == 200)
check("并发注册同名只成功一次", ok_count == 1, f"状态码={outcomes}")
check("并发注册没有产生 5xx", all(s < 500 for s in outcomes), f"状态码={outcomes}")

# ---------------------------------------------------------------------------
section("2. 并发调查同一地点：线索不重复")
name_i, token_i, _ = new_user("并发调查")
with ThreadPoolExecutor(max_workers=5) as pool:
    futures = [pool.submit(call, "POST", f"/api/cases/{CASE}/investigate",
                           {"locationKey": "lobby"}, token_i) for _ in range(5)]
    inv_outcomes = [f.result()[0] for f in futures]
check("并发调查均未产生 5xx", all(s < 500 for s in inv_outcomes), f"状态码={inv_outcomes}")

st, clues = call("GET", f"/api/cases/{CASE}/clues", token=token_i)
clue_list = clues.get("data") if isinstance(clues.get("data"), list) else []
ids = [c["id"] for c in clue_list]
check("并发调查后线索无重复", len(ids) == len(set(ids)),
      f"总数={len(ids)} 去重后={len(set(ids))}")

st, locs = call("GET", f"/api/cases/{CASE}/locations", token=token_i)
lobby = next((x for x in (locs.get("data") or []) if x.get("locationKey") == "lobby"), {})
check("并发调查后大厅线索进度不超过总量",
      (lobby.get("foundClueCount") or 0) <= (lobby.get("clueCount") or 0),
      f"found={lobby.get('foundClueCount')} total={lobby.get('clueCount')}")

# ---------------------------------------------------------------------------
section("3. 并发建立同一条证据关联：只应成功一次")
name_e, token_e, _ = new_user("并发证据")
prepare_progress(token_e)
st, clues = call("GET", f"/api/cases/{CASE}/clues", token=token_e)
clue_list = clues.get("data") if isinstance(clues.get("data"), list) else []
check("并发证据测试账号已获得 ≥2 条线索", len(clue_list) >= 2, f"n={len(clue_list)}")

if len(clue_list) >= 2:
    a_id, b_id = clue_list[0]["id"], clue_list[1]["id"]
    link_body = {"fromClueId": a_id, "toClueId": b_id, "relationType": "SUPPORTS", "note": "并发"}
    with ThreadPoolExecutor(max_workers=5) as pool:
        futures = [pool.submit(call, "POST", f"/api/cases/{CASE}/evidence-links",
                               dict(link_body), token_e) for _ in range(5)]
        link_outcomes = [f.result()[0] for f in futures]
    ok_links = sum(1 for s in link_outcomes if s == 200)
    check("并发建同一关联只成功一次", ok_links == 1, f"状态码={link_outcomes}")
    check("并发建关联没有产生 5xx", all(s < 500 for s in link_outcomes), f"状态码={link_outcomes}")

    st, board = call("GET", f"/api/cases/{CASE}/evidence-links", token=token_e)
    links = ((board.get("data") or {}).get("links") or []) if isinstance(board.get("data"), dict) else []
    check("证据板上该关联只有一条", len(links) == 1, f"links={len(links)}")

# ---------------------------------------------------------------------------
section("4. 并发发送多条对话：用户消息不丢")
name_c, token_c, _ = new_user("并发对话")
prepare_progress(token_c)
MESSAGES = [f"并发提问第{i}条：大厅当时有谁？" for i in range(1, 4)]
with ThreadPoolExecutor(max_workers=3) as pool:
    futures = [pool.submit(call, "POST", f"/api/cases/{CASE}/chat",
                           {"npcId": 1, "message": m}, token_c) for m in MESSAGES]
    chat_outcomes = [f.result()[0] for f in futures]
check("并发对话均未产生 5xx", all(s < 500 for s in chat_outcomes), f"状态码={chat_outcomes}")

st, hist = call("GET", f"/api/cases/{CASE}/chat?npcId=1", token=token_c)
msgs = hist.get("data") if isinstance(hist.get("data"), list) else []
user_msgs = [m for m in msgs if (m.get("role") or "").lower() in ("user", "player")]
blob = json.dumps(user_msgs, ensure_ascii=False)
missing = [m for m in MESSAGES if m not in blob]
check("并发发送的每条用户消息都落库了", missing == [],
      f"已存={len(user_msgs)} 丢失={missing}")

# ---------------------------------------------------------------------------
section("5. 并发重复提交结案：奖励只能发一次（差分法）")
name_single, token_single, _ = new_user("单次提交")
prepare_progress(token_single)
st, clues_s = call("GET", f"/api/cases/{CASE}/clues", token=token_single)
ids_single = [c["id"] for c in (clues_s.get("data") or [])]

name_conc, token_conc, _ = new_user("并发提交")
prepare_progress(token_conc)
st, clues_c = call("GET", f"/api/cases/{CASE}/clues", token=token_conc)
ids_conc = [c["id"] for c in (clues_c.get("data") or [])]

check("两个对照账号的线索集合一致", sorted(ids_single) == sorted(ids_conc),
      f"single={len(ids_single)} conc={len(ids_conc)}")

TEXT = {
    "hypothesis": "受害者在屋顶水箱中被发现，死亡时间与监控空窗期吻合。",
    "keyPeople": "电梯中的最后一名同行者、酒店员工。",
    "keyTimeline": "1 月 31 日晚进入电梯，2 月 19 日遗体被发现。",
    "reasoningText": "电梯行为异常、监控中断与水箱位置共同指向一起非意外的死亡事件，"
                     "但公开资料不足以给出确定结论，本推理仅代表游戏假设。",
    "conclusion": "基于现有公开档案，这是一起原因尚未确定的死亡事件。",
}
body_single = dict(TEXT, evidenceClueIds=ids_single)
body_conc = dict(TEXT, evidenceClueIds=ids_conc)

st_single, res_single = call("POST", f"/api/cases/{CASE}/submit", body_single, token_single)
check("单次提交成功", st_single == 200, f"http={st_single} msg={str(res_single.get('message'))[:60]}")

with ThreadPoolExecutor(max_workers=3) as pool:
    futures = [pool.submit(call, "POST", f"/api/cases/{CASE}/submit",
                           dict(body_conc), token_conc) for _ in range(3)]
    conc_results = [f.result() for f in futures]
conc_codes = [c for c, _ in conc_results]
check("并发重复提交没有产生 5xx", all(s < 500 for s in conc_codes), f"状态码={conc_codes}")

p_single = profile(token_single)
p_conc = profile(token_conc)

check("并发提交与单次提交的 EXP 相同（奖励没有重复发放）",
      p_single.get("exp") == p_conc.get("exp"),
      f"single={p_single.get('exp')} conc={p_conc.get('exp')}")
check("并发提交与单次提交的金币相同",
      p_single.get("coins") == p_conc.get("coins"),
      f"single={p_single.get('coins')} conc={p_conc.get('coins')}")

rec_single = [r for r in (p_single.get("history") or []) if r.get("caseId") == CASE]
rec_conc = [r for r in (p_conc.get("history") or []) if r.get("caseId") == CASE]
check("单次提交只有 1 条案件记录", len(rec_single) == 1, f"n={len(rec_single)}")
check("并发提交也只有 1 条案件记录（未重复建档）", len(rec_conc) == 1, f"n={len(rec_conc)}")
if rec_single and rec_conc:
    check("并发提交与单次提交的分数相同",
          rec_single[0].get("totalScore") == rec_conc[0].get("totalScore"),
          f"single={rec_single[0].get('totalScore')} conc={rec_conc[0].get('totalScore')}")
    check("案件被标记为已完成", rec_conc[0].get("status") == "COMPLETED",
          f"status={rec_conc[0].get('status')}")

st, ldb = call("GET", "/api/ranking?type=score")
entries = ldb.get("data") if isinstance(ldb.get("data"), list) else []
if isinstance(ldb.get("data"), dict):
    entries = ldb["data"].get("entries") or []
names = [e.get("username") for e in entries]
dups = sorted({n for n in names if n and names.count(n) > 1})
check("排行榜中同一账号不重复出现", dups == [], f"重复={dups}")

# ---------------------------------------------------------------------------
section("6. 并发读写不同地点：进度一致")
# 只并发调查**同为公开**的两个地点。water-system 需要先调查过 lobby，
# 并发发起时它可能先于 lobby 被判定，那是正确的门控拒绝（400），不是缺陷。
name_m, token_m, _ = new_user("并发多地点")
PUBLIC_LOCATIONS = ["lobby", "elevator"]
with ThreadPoolExecutor(max_workers=2) as pool:
    futures = [pool.submit(call, "POST", f"/api/cases/{CASE}/investigate",
                           {"locationKey": k}, token_m) for k in PUBLIC_LOCATIONS]
    multi = [f.result()[0] for f in futures]
check("并发调查两个公开地点均成功", all(s == 200 for s in multi), f"状态码={multi}")

# 门控地点串行调查（此时 lobby 已完成）
st, gated = call("POST", f"/api/cases/{CASE}/investigate", {"locationKey": "water-system"},
                 token=token_m)
check("门控地点在满足条件后调查成功", st == 200, f"http={st}")

# 门控地点在条件不满足时必须被拒绝（用另一个全新账号复现）
name_g, token_g, _ = new_user("门控拒绝")
st, gated_reject = call("POST", f"/api/cases/{CASE}/investigate",
                        {"locationKey": "water-system"}, token=token_g)
check("未满足前置条件时门控地点被拒绝", 400 <= st < 500,
      f"http={st} msg={str(gated_reject.get('message'))[:50]}")

st, p = call("GET", f"/api/cases/{CASE}/progress", token=token_m)
pct = (p.get("data") or {}).get("overallPercent")
st, lst = call("GET", "/api/cases", token=token_m)
row = next((c for c in (lst.get("data") or []) if c.get("id") == CASE), {})
list_pct = (row.get("playerProgress") or {}).get("percent")
check("详情页与列表页完成度一致（并发写之后仍一致）", pct == list_pct,
      f"detail={pct} list={list_pct}")

# ---------------------------------------------------------------------------
section("7. 并发提交同一谜题：不能因为竞态而失败")
name_p, token_p, _ = new_user("并发谜题")
prepare_progress(token_p)
st, pz = call("GET", f"/api/cases/{CASE}/puzzles", token=token_p)
puzzles = pz.get("data") if isinstance(pz.get("data"), list) else []
check("能取到本案谜题列表", len(puzzles) > 0, f"n={len(puzzles)}")

if puzzles:
    pid = puzzles[0]["id"]
    # 故意用错误答案：既走「首次建档」这条插入路径，又不会解锁隐藏线索
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(call, "POST", f"/api/cases/{CASE}/puzzles/{pid}",
                               {"answer": "一个肯定不对的答案"}, token_p) for _ in range(3)]
        pz_codes = [f.result()[0] for f in futures]
    check("并发提交同一谜题不产生 5xx", all(s < 500 for s in pz_codes), f"状态码={pz_codes}")
    check("并发提交同一谜题不因竞态被拒", all(s == 200 for s in pz_codes), f"状态码={pz_codes}")
    st, pz2 = call("GET", f"/api/cases/{CASE}/puzzles", token=token_p)
    check("并发提交后谜题列表仍可读", st == 200, f"http={st}")

# ---------------------------------------------------------------------------
section("汇总")
source = open(os.path.abspath(__file__), encoding="utf-8").read()
# 只数「行首（可带缩进）就是 check(」的调用：既排除 def check( 自身，
# 也排除下面这行 r"check\(" 里的字面量。再减 1 是因为自检执行时自己还没计数。
declared = len(re.findall(r"^\s*check\(", source, re.M)) - 1
check("源码声明的检查项都已执行", executed >= declared,
      f"declared={declared} executed={executed}")

failed = [r for r in results if not r["pass"]]
report = {"base": BASE, "declared": declared, "executed": executed,
          "failed": len(failed), "results": results}
os.makedirs(os.path.dirname(REPORT), exist_ok=True)
with open(REPORT, "w", encoding="utf-8") as fh:
    json.dump(report, fh, ensure_ascii=False, indent=2)

print(f"\nSUMMARY declared={declared} executed={executed} failed={len(failed)}", flush=True)
print(f"报告已写入 {REPORT}", flush=True)
if failed:
    print("\n失败项：", flush=True)
    for r in failed:
        print(f"  - {r['name']}  ({r['detail']})", flush=True)
    sys.exit(1)
print("全部通过。", flush=True)
