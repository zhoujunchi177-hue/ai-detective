"""MindTrace 发布前全量测试 —— 鉴权 / 越权 / 注入 / 隐藏数据 / 编码 边界。

对应验收清单的：
  Part 3  数据库完整性（唯一性、越界值）
  Part 4  注册 / 登录边界矩阵
  Part 5  权限与越权（A / B 两个账号交叉访问）
  Part 6  案件列表 / 详情边界
  Part 7  调查地图（未解锁节点直取）
  Part 8  线索系统（隐藏线索不可直接获取）
  Part 21 安全（API Key 不泄露 / SQL 注入 / XSS / 令牌异常 / 报错不泄露内部实现）

设计原则：
  * 只读 + 注册一次性随机账号，**不改动任何既有玩家的进度**。
  * 每条检查独立可读，失败时打印期望与实际。
  * 结尾自检：源码里声明了多少条 check()，就必须至少执行多少次 ——
    防止「某条断言被静默吞掉，日志看起来全绿」。
"""

import json
import os
import random
import re
import string
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.environ.get("MINDTRACE_API", "http://127.0.0.1:8080")
REPORT = os.path.join(REPO, ".runtime", "rc-security-report.json")

results = []
executed = 0
ALPHABET = string.ascii_lowercase + string.digits


def rand(n):
    return "".join(random.choice(ALPHABET) for _ in range(n))


def parse(raw):
    try:
        return json.loads(raw)
    except Exception:
        return {"_raw": raw}


def call(method, path, body=None, token=None, auth=None, timeout=90):
    url = BASE + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    if auth is not None:
        headers["Authorization"] = auth
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, parse(resp.read().decode("utf-8", "replace")), dict(resp.headers)
    except urllib.error.HTTPError as exc:
        return exc.code, parse(exc.read().decode("utf-8", "replace")), dict(exc.headers)
    except Exception as exc:  # 连接层失败
        return 0, {"_error": repr(exc)}, {}


def check(name, ok, detail=""):
    global executed
    executed += 1
    results.append({"name": name, "pass": bool(ok), "detail": detail})
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {name}" + (f"   —— {detail}" if detail else ""), flush=True)


def register(username=None, nickname="测试调查员", password="pass123456"):
    username = username or ("rc" + rand(10))
    status, body, _ = call("POST", "/api/auth/register",
                           {"username": username, "nickname": nickname, "password": password})
    token = (body.get("data") or {}).get("token") if isinstance(body, dict) else None
    return status, body, token


def login(username, password):
    return call("POST", "/api/auth/login", {"username": username, "password": password})


def section(title):
    print("\n" + "=" * 72 + f"\n{title}\n" + "=" * 72, flush=True)


# ---------------------------------------------------------------------------
section("0. 前置：健康检查与测试账号")
status, health, _ = call("GET", "/api/health")
check("健康检查可用", status == 200 and (health.get("data") or {}).get("status") == "UP",
      f"http={status}")

suffix = uuid.uuid4().hex[:8]
user_a = "rcA" + suffix
user_b = "rcB" + suffix
user_c = "rcC" + suffix
status_a, body_a, token_a = register(user_a, nickname="甲调查员")
status_b, body_b, token_b = register(user_b, nickname="乙调查员")
status_c, body_c, token_c = register(user_c, nickname="丙调查员")
check("测试账号 A/B/C 注册成功", status_a == 200 and status_b == 200 and status_c == 200,
      f"a={status_a} b={status_b} c={status_c}")
if not (token_a and token_b and token_c):
    print("!! 无法注册测试账号，后续检查无法进行", flush=True)
    sys.exit(2)

# ---------------------------------------------------------------------------
section("1. 匿名访问：公开只读接口不得泄露任何玩家数据")
PUBLIC_GETS = [
    "/api/cases",
    "/api/cases/1",
    "/api/cases/1/timeline",
    "/api/cases/1/sources",
    "/api/cases/1/suspects",
    "/api/cases/1/npcs",
    "/api/cases/1/puzzles",
    "/api/ranking?type=score",
    "/api/ranking?type=cases",
    "/api/ranking?type=level",
]
for path in PUBLIC_GETS:
    st, body, _ = call("GET", path)
    check(f"匿名 GET {path} 返回 200", st == 200, f"http={st}")

st, body, _ = call("GET", "/api/cases/1/clues")
check("匿名 GET 线索返回空（不泄露他人线索）",
      st == 200 and isinstance(body.get("data"), list) and len(body["data"]) == 0,
      f"http={st} count={len(body.get('data') or []) if isinstance(body.get('data'), list) else 'n/a'}")

st, body, _ = call("GET", "/api/cases/1/evidence-links")
nodes = ((body.get("data") or {}).get("nodes") or []) if isinstance(body.get("data"), dict) else None
check("匿名 GET 证据板无节点（不泄露他人证据链）", st == 200 and nodes == [],
      f"http={st} nodes={0 if nodes is None else len(nodes)}")

st, body, _ = call("GET", "/api/cases/1/history")
hist_total = (body.get("data") or {}).get("total") if isinstance(body.get("data"), dict) else None
check("匿名 GET 调查日志返回空（不泄露他人日志）",
      st == 200 and hist_total == 0, f"http={st} total={hist_total}")

st, body, _ = call("GET", "/api/cases/1/locations")
locs = body.get("data") if isinstance(body.get("data"), list) else []
anon_investigated = [x for x in locs if x.get("status") in ("INVESTIGATED", "COMPLETED")]
check("匿名 GET 地图节点全部为未调查状态（不泄露他人进度）",
      st == 200 and anon_investigated == [], f"http={st} investigated={len(anon_investigated)}")

st, body, _ = call("GET", "/api/cases/1/progress")
prog = body.get("data") or {}
check("匿名 GET 进度不显示他人完成度",
      st == 200 and not prog.get("completed") and (prog.get("overallPercent") or 0) == 0,
      f"http={st} completed={prog.get('completed')} pct={prog.get('overallPercent')}")

section("2. 匿名写操作与个人接口必须 401")
ANON_WRITES = [
    ("POST", "/api/cases/1/investigate", {"locationKey": "lobby"}),
    ("POST", "/api/cases/1/chat", {"npcId": 1, "message": "你好"}),
    ("POST", "/api/cases/1/reasoning", {"hypothesis": "测试"}),
    ("POST", "/api/cases/1/evidence-links",
     {"fromClueId": 1, "toClueId": 2, "relationType": "SUPPORTS", "note": ""}),
    ("DELETE", "/api/cases/1/evidence-links/1", None),
]
for method, path, body in ANON_WRITES:
    st, resp, _ = call(method, path, body)
    check(f"匿名 {method} {path} 被拒绝 401", st == 401, f"http={st}")

st, body, _ = call("GET", "/api/cases/1/chat?npcId=1")
check("匿名 GET 聊天记录被拒绝 401", st == 401, f"http={st}")

st, body, _ = call("GET", "/api/user/profile")
check("匿名 GET 个人档案被拒绝 401", st == 401, f"http={st}")

# ---------------------------------------------------------------------------
section("3. 令牌异常")
st, _, _ = call("GET", "/api/user/profile", token=token_a)
check("合法令牌可访问个人档案", st == 200, f"http={st}")

for label, header in [
    ("缺少 Authorization 头", None),
    ("Bearer 后为空", "Bearer "),
    ("结构非法的令牌", "Bearer not.a.jwt"),
    ("错误认证方案", "Token " + token_a),
    ("空字符串令牌", ""),
]:
    st, _, _ = call("GET", "/api/user/profile", auth=header)
    check(f"令牌异常（{label}）被拒绝 401", st == 401, f"http={st}")

tampered = token_a[:-3] + ("aaa" if not token_a.endswith("aaa") else "bbb")
st, _, _ = call("GET", "/api/user/profile", token=tampered)
check("签名被篡改的令牌被拒绝 401", st == 401, f"http={st}")

parts = token_a.split(".")
if len(parts) == 3:
    forged = parts[0] + "." + parts[1] + "." + ("A" * len(parts[2]))
    st, _, _ = call("GET", "/api/user/profile", token=forged)
    check("伪造签名的令牌被拒绝 401", st == 401, f"http={st}")
else:
    check("伪造签名的令牌被拒绝 401", False, "令牌不是三段式，无法构造")

st, body, _ = call("GET", "/api/user/profile", token=token_a)
profile_name = ((body.get("data") or {}).get("username")
                if isinstance(body.get("data"), dict) else None)
check("个人档案返回的是自己的账号", profile_name == user_a, f"got={profile_name}")

# ---------------------------------------------------------------------------
section("4. 注册边界矩阵（用户名 3-20 位字母数字下划线 / 昵称 ≤24 / 密码 6-32）")
REGISTER_CASES = [
    ("用户名 2 位应拒绝", rand(2), "昵称", "pass123456", False),
    ("用户名 3 位应接受", rand(3), "昵称", "pass123456", True),
    ("用户名 20 位应接受", rand(20), "昵称", "pass123456", True),
    ("用户名 21 位应拒绝", rand(21), "昵称", "pass123456", False),
    ("用户名含中文应拒绝", "调查" + rand(3), "昵称", "pass123456", False),
    ("用户名含空格应拒绝", "ab " + rand(3), "昵称", "pass123456", False),
    ("用户名含连字符应拒绝", "ab-" + rand(3), "昵称", "pass123456", False),
    ("用户名空应拒绝", "", "昵称", "pass123456", False),
    ("用户名 SQL 注入串应拒绝", "' OR '1'='1", "昵称", "pass123456", False),
    ("用户名 XSS 串应拒绝", "<script>", "昵称", "pass123456", False),
    ("昵称 24 字应接受", rand(12), "名" * 24, "pass123456", True),
    ("昵称 25 字应拒绝", rand(12), "名" * 25, "pass123456", False),
    ("昵称空应拒绝", rand(12), "", "pass123456", False),
    ("密码 5 位应拒绝", rand(12), "昵称", "pass1", False),
    ("密码 6 位应接受", rand(12), "昵称", "pass12", True),
    ("密码 32 位应接受", rand(12), "昵称", "p" * 32, True),
    ("密码 33 位应拒绝", rand(12), "昵称", "p" * 33, False),
]
for label, uname, nick, pwd, should_pass in REGISTER_CASES:
    st, body, _ = call("POST", "/api/auth/register",
                       {"username": uname, "nickname": nick, "password": pwd})
    ok = (st == 200) if should_pass else (400 <= st < 500)
    check(label, ok, f"http={st} msg={str(body.get('message'))[:60]}")

dup_user = "rcDup" + suffix
st1, _, _ = call("POST", "/api/auth/register",
                 {"username": dup_user, "nickname": "重名", "password": "pass123456"})
st2, body2, _ = call("POST", "/api/auth/register",
                     {"username": dup_user, "nickname": "重名", "password": "pass123456"})
check("重复用户名注册被拒绝", st1 == 200 and 400 <= st2 < 500,
      f"first={st1} second={st2} msg={str(body2.get('message'))[:60]}")

# ---------------------------------------------------------------------------
section("5. 登录边界")
st, body, _ = login(user_a, "pass123456")
check("正确凭据登录成功", st == 200 and bool((body.get("data") or {}).get("token")), f"http={st}")

st, _, _ = login(user_a, "wrongpassword")
check("错误密码登录被拒绝 401", st == 401, f"http={st}")

st, _, _ = login("no_such_user_" + suffix, "pass123456")
check("不存在的账号登录被拒绝 401", st == 401, f"http={st}")

st, _, _ = login("' OR '1'='1", "pass123456")
check("用户名 SQL 注入登录被拒绝 401", st == 401, f"http={st}")

st, _, _ = login(user_a, "' OR '1'='1")
check("密码 SQL 注入登录被拒绝 401", st == 401, f"http={st}")

st, _, _ = call("POST", "/api/auth/login", {"username": "", "password": "pass123456"})
check("空用户名登录被拒绝 400", st == 400, f"http={st}")

st, _, _ = call("POST", "/api/auth/login", {"username": user_a, "password": ""})
check("空密码登录被拒绝 400", st == 400, f"http={st}")

# ---------------------------------------------------------------------------
section("6. A / B 跨账号越权")
# A 制造真实进度
st, inv, _ = call("POST", "/api/cases/1/investigate", {"locationKey": "lobby"}, token=token_a)
check("A 调查公开地点成功", st == 200, f"http={st}")
call("POST", "/api/cases/1/investigate", {"locationKey": "elevator"}, token=token_a)
st, inv2, _ = call("POST", "/api/cases/1/investigate", {"locationKey": "water-system"}, token=token_a)
check("A 调查门控地点（供水系统）成功", st == 200, f"http={st}")

st, aclues, _ = call("GET", "/api/cases/1/clues", token=token_a)
a_clue_list = aclues.get("data") or []
check("A 已发现线索", st == 200 and len(a_clue_list) > 0, f"count={len(a_clue_list)}")

a_link_id = None
if len(a_clue_list) >= 2:
    ids = [c["id"] for c in a_clue_list[:2]]
    st, link, _ = call("POST", "/api/cases/1/evidence-links",
                       {"fromClueId": ids[0], "toClueId": ids[1],
                        "relationType": "SUPPORTS", "note": "甲的证据链"},
                       token=token_a)
    a_link_id = (link.get("data") or {}).get("id") if isinstance(link.get("data"), dict) else None
    check("A 建立证据关联成功", st == 200 and a_link_id is not None, f"http={st}")

st, chat, _ = call("POST", "/api/cases/1/chat", {"npcId": 1, "message": "大厅里当时有谁？"},
                   token=token_a)
check("A 与 NPC 对话成功（含 AI 调用）", st == 200, f"http={st}")

# A 的基线
st, ah, _ = call("GET", "/api/cases/1/history", token=token_a)
a_hist_before = (ah.get("data") or {}).get("total")
st, ap, _ = call("GET", "/api/cases/1/progress", token=token_a)
a_pct_before = (ap.get("data") or {}).get("overallPercent")

# B 交叉访问
st, bh, _ = call("GET", "/api/cases/1/history", token=token_b)
b_hist = (bh.get("data") or {}).get("total")
check("B 看不到 A 的调查日志", st == 200 and b_hist == 0, f"http={st} total={b_hist}")

st, bc, _ = call("GET", "/api/cases/1/chat?npcId=1", token=token_b)
b_chat = bc.get("data") if isinstance(bc.get("data"), list) else None
check("B 看不到 A 的对话记录", st == 200 and b_chat == [], f"http={st} n={0 if b_chat is None else len(b_chat)}")

st, bcl, _ = call("GET", "/api/cases/1/clues", token=token_b)
b_clues = bcl.get("data") if isinstance(bcl.get("data"), list) else None
check("B 看不到 A 的线索", st == 200 and b_clues == [], f"http={st} n={0 if b_clues is None else len(b_clues)}")

st, bb, _ = call("GET", "/api/cases/1/evidence-links", token=token_b)
b_nodes = ((bb.get("data") or {}).get("nodes") or []) if isinstance(bb.get("data"), dict) else None
b_links = ((bb.get("data") or {}).get("links") or []) if isinstance(bb.get("data"), dict) else None
check("B 的证据板是空的（无 A 的节点与连线）",
      st == 200 and b_nodes == [] and b_links == [], f"http={st}")

st, bp, _ = call("GET", "/api/cases/1/progress", token=token_b)
b_pct = (bp.get("data") or {}).get("overallPercent")
check("B 的进度不受 A 影响", st == 200 and (b_pct or 0) == 0, f"http={st} pct={b_pct}")

st, bprof, _ = call("GET", "/api/user/profile", token=token_b)
b_name = ((bprof.get("data") or {}).get("username") if isinstance(bprof.get("data"), dict) else None)
check("B 的个人档案是 B 自己", b_name == user_b, f"got={b_name}")

if a_link_id is not None:
    st, _, _ = call("DELETE", f"/api/cases/1/evidence-links/{a_link_id}", token=token_b)
    check("B 删除 A 的证据关联失败（404）", st == 404, f"http={st}")
    st, still, _ = call("GET", "/api/cases/1/evidence-links", token=token_a)
    still_links = ((still.get("data") or {}).get("links") or []) if isinstance(still.get("data"), dict) else []
    check("A 的证据关联未被 B 删除", len(still_links) >= 1, f"links={len(still_links)}")

# B 自己调查后，A 的进度不受影响
st, _, _ = call("POST", "/api/cases/1/investigate", {"locationKey": "lobby"}, token=token_b)
check("B 独立调查同一地点成功", st == 200, f"http={st}")

st, ah2, _ = call("GET", "/api/cases/1/history", token=token_a)
a_hist_after = (ah2.get("data") or {}).get("total")
st, ap2, _ = call("GET", "/api/cases/1/progress", token=token_a)
a_pct_after = (ap2.get("data") or {}).get("overallPercent")
check("B 的操作没有改变 A 的日志条数", a_hist_after == a_hist_before,
      f"before={a_hist_before} after={a_hist_after}")
check("B 的操作没有改变 A 的完成度", a_pct_after == a_pct_before,
      f"before={a_pct_before} after={a_pct_after}")

# ---------------------------------------------------------------------------
section("7. 非法参数 / SQL 注入不得 500")
INJECT_PATHS = [
    "/api/cases/abc",
    "/api/cases/1%20OR%201=1",
    "/api/cases/1'",
    "/api/cases/1;DROP%20TABLE%20users;--",
    "/api/cases/-1",
    "/api/cases/999999",
]
for path in INJECT_PATHS:
    st, body, _ = call("GET", path)
    check(f"非法案件路径 {path} 不返回 5xx", st < 500, f"http={st} msg={str(body.get('message'))[:70]}")

st, body, _ = call("GET", "/api/cases/1/chat?npcId=abc", token=token_a)
check("非数字 npcId 不返回 5xx", st < 500, f"http={st}")

st, body, _ = call("GET", "/api/cases/1/history?keyword=" + urllib.parse.quote("' OR '1'='1"),
                   token=token_a)
check("日志关键词注入被安全处理", st == 200, f"http={st}")

st, body, _ = call("GET", "/api/cases/1/history?page=-5&size=-1", token=token_a)
check("日志非法分页参数不返回 5xx", st < 500, f"http={st}")

st, body, _ = call("GET", "/api/ranking?type=" + urllib.parse.quote("score' OR '1'='1"))
check("排行榜类型注入不返回 5xx", st < 500, f"http={st}")

section("8. 报错信息不得泄露内部实现")
LEAK_MARKERS = ["com.mindtrace", "java.lang", "org.springframework", "Exception", "at com.", "SQLException"]
leaky = []
for path in INJECT_PATHS + ["/api/cases/1/chat?npcId=abc"]:
    st, body, _ = call("GET", path, token=token_a)
    msg = str(body.get("message") or "")
    if any(m in msg for m in LEAK_MARKERS):
        leaky.append((path, msg[:90]))
check("错误响应不含堆栈/包名等内部细节", leaky == [],
      f"泄露样本={leaky}" if leaky else "无泄露")

# ---------------------------------------------------------------------------
section("8b. 不存在的路径返回 404，而不是 500")
# 前端把接口路径写错（例如 /api/user/profile 写成 /api/profile）时，
# 请求会落到静态资源处理器并抛 NoResourceFoundException。此前它被兜底
# handler 变成 500，还打出一整条 ERROR 堆栈 —— 纯粹的「路径写错了」既污染
# 5xx 监控，也让排查方向跑偏。这类请求本质就是 404。
MISSING_PATHS = ["/api/profile", "/api/nope", "/api/cases/1/nope", "/api/cases/1/clues/extra"]
for path in MISSING_PATHS:
    st, body, _ = call("GET", path, token=token_a)
    check(f"不存在的路径 {path} 返回 404", st == 404, f"http={st}")
    check(f"不存在的路径 {path} 返回 JSON 而非 HTML", "请求的接口不存在" in str(body.get("message") or ""),
          f"msg={str(body.get('message'))[:60]}")

# ---------------------------------------------------------------------------
section("9. 隐藏线索不可直接获取")
st, cl, _ = call("GET", "/api/cases/1/clues", token=token_c)
clue_list = cl.get("data") if isinstance(cl.get("data"), list) else []
hidden_found = [c for c in clue_list if c.get("isHidden") in (True, 1, "1")]
check("新账号线索列表不含隐藏线索", hidden_found == [],
      f"hidden={[c.get('clueCode') for c in hidden_found]}")

st, det, _ = call("GET", "/api/cases/1", token=token_c)
disc = ((det.get("data") or {}).get("discoveredClues") or []) if isinstance(det.get("data"), dict) else []
hidden_in_detail = [c for c in disc if c.get("isHidden") in (True, 1, "1")]
check("案件详情的新账号线索也不含隐藏线索", hidden_in_detail == [],
      f"hidden={[c.get('clueCode') for c in hidden_in_detail]}")

st, bpz, _ = call("GET", "/api/cases/1/puzzles", token=token_c)
puzzles = bpz.get("data") if isinstance(bpz.get("data"), list) else []
leaked_answers = [p for p in puzzles if p.get("correctAnswer")]
check("谜题列表不下发正确答案", leaked_answers == [],
      f"泄露={[p.get('id') for p in leaked_answers]}")

# ---------------------------------------------------------------------------
section("10. API Key 不泄露")
st, health2, headers = call("GET", "/api/health")
raw_health = json.dumps(health2, ensure_ascii=False)
check("健康检查响应不含密钥片段", "sk-" not in raw_health and "apiKey" not in raw_health,
      raw_health[:120])
header_blob = " ".join(f"{k}:{v}" for k, v in (headers or {}).items())
check("响应头不含密钥", "sk-" not in header_blob, header_blob[:120])

st, prof, _ = call("GET", "/api/user/profile", token=token_a)
check("个人档案响应不含密钥", "sk-" not in json.dumps(prof, ensure_ascii=False))

st, cd, _ = call("GET", "/api/cases/1", token=token_a)
check("案件详情响应不含密钥", "sk-" not in json.dumps(cd, ensure_ascii=False))

# ---------------------------------------------------------------------------
section("11. UTF-8 / 特殊字符存取一致性")
NICK_SPECIAL = '调查员🚀"引号"O\'Brien'
st, body, tok_special = register(nickname=NICK_SPECIAL)
check("含中文/emoji/引号的昵称注册成功", st == 200, f"http={st}")
if tok_special:
    st, prof, _ = call("GET", "/api/user/profile", token=tok_special)
    got_nick = ((prof.get("data") or {}).get("nickname") if isinstance(prof.get("data"), dict) else None)
    check("特殊字符昵称原样读回", got_nick == NICK_SPECIAL, f"got={got_nick!r}")

st, body, _ = call("POST", "/api/cases/1/chat",
                   {"npcId": 1, "message": "电梯🚀在哪里？<script>alert(1)</script>"},
                   token=token_a)
check("含 emoji 与脚本片段的对话请求被接受", st == 200, f"http={st}")
st, hist, _ = call("GET", "/api/cases/1/chat?npcId=1", token=token_a)
msgs = hist.get("data") if isinstance(hist.get("data"), list) else []
raw = json.dumps(msgs, ensure_ascii=False)
check("对话内容按原样存储（未破坏编码）",
      "电梯🚀在哪里？" in raw, f"n={len(msgs)}")
check("对话内容未被服务端错误转义成 HTML 实体",
      "&lt;script&gt;" not in raw, "未发现实体化")

# ---------------------------------------------------------------------------
section("12. 案件列表 / 详情边界")
st, body, _ = call("GET", "/api/cases", token=token_a)
cases = body.get("data") if isinstance(body.get("data"), list) else []
check("案件列表返回 3 个案件", st == 200 and len(cases) == 3, f"n={len(cases)}")
bad_pct = [c for c in cases if not (0 <= (c.get("playerProgress") or {}).get("percent", 0) <= 100)]
check("列表页进度百分比均在 0..100", bad_pct == [], f"越界={[c.get('id') for c in bad_pct]}")

st, body, _ = call("GET", "/api/cases/0", token=token_a)
check("不存在的案件 0 返回 4xx", 400 <= st < 500, f"http={st}")
st, body, _ = call("GET", "/api/cases/999999", token=token_a)
check("不存在的案件返回 4xx", 400 <= st < 500, f"http={st}")

# ---------------------------------------------------------------------------
section("13. 未解锁地点不可绕过前端直取")
st, body, _ = call("POST", "/api/cases/1/investigate", {"locationKey": "rooftop"}, token=token_c)
check("直取未解锁地点（rooftop）被拒绝", 400 <= st < 500,
      f"http={st} msg={str(body.get('message'))[:60]}")
st, body, _ = call("POST", "/api/cases/1/investigate", {"locationKey": "no-such-place"}, token=token_c)
check("不存在的地点被拒绝", 400 <= st < 500, f"http={st}")
st, body, _ = call("POST", "/api/cases/1/investigate", {"locationKey": ""}, token=token_c)
check("空地点被拒绝 400", st == 400, f"http={st}")

# ---------------------------------------------------------------------------
section("14. 证据关联拒绝路径")
if len(a_clue_list) >= 2:
    i0, i1 = a_clue_list[0]["id"], a_clue_list[1]["id"]
    st, _, _ = call("POST", "/api/cases/1/evidence-links",
                    {"fromClueId": i0, "toClueId": i0, "relationType": "SUPPORTS", "note": ""},
                    token=token_a)
    check("自连被拒绝", 400 <= st < 500, f"http={st}")
    st, _, _ = call("POST", "/api/cases/1/evidence-links",
                    {"fromClueId": i0, "toClueId": i1, "relationType": "SUPPORTS", "note": ""},
                    token=token_a)
    check("重复关联（不分方向）被拒绝", 400 <= st < 500, f"http={st}")
    st, _, _ = call("POST", "/api/cases/1/evidence-links",
                    {"fromClueId": i0, "toClueId": i1, "relationType": "NOT_A_TYPE", "note": ""},
                    token=token_b)
    check("关联自己未发现的线索被拒绝", 400 <= st < 500, f"http={st}")
    st, _, _ = call("POST", "/api/cases/1/evidence-links",
                    {"fromClueId": i0, "toClueId": i1, "relationType": "SUPPORTS",
                     "note": "字" * 400}, token=token_a)
    check("备注超 300 字被拒绝", 400 <= st < 500, f"http={st}")

# ---------------------------------------------------------------------------
section("15. 提交边界（空字段 / 超长文本）")
EMPTY_SUBMIT = {"hypothesis": "", "keyPeople": "甲", "keyTimeline": "甲",
                "reasoningText": "甲", "conclusion": "甲"}
st, body, _ = call("POST", "/api/cases/1/submit", EMPTY_SUBMIT, token=token_c)
check("核心假设为空被拒绝 400", st == 400, f"http={st}")

st, body, _ = call("POST", "/api/cases/1/submit",
                   {"hypothesis": "甲", "keyPeople": "甲", "keyTimeline": "甲",
                    "reasoningText": "", "conclusion": "甲"}, token=token_c)
check("推理过程为空被拒绝 400", st == 400, f"http={st}")

st, body, _ = call("POST", "/api/cases/1/submit",
                   {"hypothesis": "甲", "keyPeople": "甲", "keyTimeline": "甲",
                    "reasoningText": "甲"}, token=token_c)
check("缺少最终结论字段被拒绝 400", st == 400, f"http={st}")

st, body, _ = call("POST", "/api/cases/1/reasoning", {"hypothesis": ""}, token=token_c)
check("推理分析空假设被拒绝 400", st == 400, f"http={st}")

st, body, _ = call("POST", "/api/cases/1/reasoning", {"hypothesis": "字" * 5001}, token=token_c)
check("推理分析超 5000 字被拒绝 400", st == 400, f"http={st}")

st, body, _ = call("POST", "/api/cases/1/chat", {"npcId": 1, "message": "字" * 1001}, token=token_c)
check("对话超 1000 字被拒绝 400", st == 400, f"http={st}")

st, body, _ = call("POST", "/api/cases/1/chat", {"message": "没有 npcId"}, token=token_c)
check("对话缺少 npcId 被拒绝 400", st == 400, f"http={st}")

# ---------------------------------------------------------------------------
section("16. 谜题边界")
st, body, _ = call("POST", "/api/cases/1/puzzles/999999",
                   {"answer": "x"}, token=token_c)
check("不存在的谜题返回 4xx", 400 <= st < 500, f"http={st} msg={str(body.get('message'))[:50]}")
st, body, _ = call("POST", "/api/cases/1/puzzles/1", {"answer": ""}, token=token_c)
check("空答案不返回 5xx", st < 500, f"http={st}")
st, body, _ = call("POST", "/api/cases/1/puzzles/1", {"answer": "字" * 5000}, token=token_c)
check("超长答案不返回 5xx", st < 500, f"http={st}")

# ---------------------------------------------------------------------------
section("汇总")
failed = [r for r in results if not r["pass"]]
source = open(os.path.abspath(__file__), encoding="utf-8").read()
# 只数「行首（可带缩进）就是 check(」的调用：既排除 def check( 自身，
# 也排除下面这行 r"check\(" 里的字面量。再减 1 是因为自检执行时自己还没计数。
declared = len(re.findall(r"^\s*check\(", source, re.M)) - 1
check("源码声明的检查项都已执行（无静默丢失）", executed >= declared,
      f"declared={declared} executed={executed}")

failed = [r for r in results if not r["pass"]]
report = {
    "base": BASE,
    "declared": declared,
    "executed": executed,
    "failed": len(failed),
    "results": results,
}
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
