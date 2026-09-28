"""把 CASE-001 打满，验证 GameService.persist 里 score>=80 的**奖励加成分支**。

为什么需要这个脚本 —— 该分支在所有既有验收里都没被走到：
  - 后端单测没有奖励公式测试
  - verify-api.ps1 用全新账号（分数 <= 25，加成分支不可达）
  - e2e-full-loop.mjs 那次得分 30（同上）

做法：注册新账号 → 自适应调查全部 5 个地点 → 解全部 3 个谜题 →
用关键词和 NPC 对话解锁 NPC 线索 → 用满档文本 + 全部线索提交结案，
然后断言实际奖励等于公式 `score*2 + (score>=80 ? 60 : 0)` /
`score + (score>=80 ? 30 : 0)`。

前置：后端已启动（默认 127.0.0.1:8080）。不需要真实 AI —— 分数由 Java 计算，
AI 只生成文字评价。但若要一并验证 AI 结案报告，需配置真实 Key。

用法：
    python scripts/verify-reward-bonus.py

输出：`.runtime/reward-bonus-report.json`

注意：谜题答案硬编码自 `puzzles.correct_answer`，改题库时需同步。
"""
import json
import os
import time
import urllib.error
import urllib.request

BASE = os.environ.get("MINDTRACE_API", "http://127.0.0.1:8080/api")
REPORT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                      ".runtime", "reward-bonus-report.json")

# 谜题正确答案（来自 puzzles.correct_answer）
PUZZLE_ANSWERS = {
    1: "checkin,elevator,lastseen,complaints,bodyfound",
    2: "guest-report,maintenance,rooftop-tank",
    3: "unverified",
}
# NPC 关键词线索：CLUE-004 靠 npc1 说「电梯」，CLUE-012 靠 npc2 说「网络」
NPC_KEYWORDS = [(1, "电梯"), (2, "网络")]

log = []


def call(method, path, body=None, token=None, timeout=180):
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


def note(msg):
    log.append(msg)
    print(msg, flush=True)


name = "bonus" + str(int(time.time() * 1000))
reg, _ = call("POST", "/auth/register",
              {"username": name, "nickname": "加成验收", "password": "bonus123456"})
token = reg["data"]["token"]
note(f"账号 {name}")

# ---------- 1. 自适应推进：反复调查可用地点 ----------
for round_no in range(1, 7):
    locs, _ = call("GET", "/cases/1/locations", token=token)
    nodes = locs.get("data") or []
    pending = [n for n in nodes if n.get("status") == "AVAILABLE"]
    if not pending:
        note(f"第 {round_no} 轮：没有 AVAILABLE 节点了（共 {len(nodes)} 个）")
        break
    for node in pending:
        result, code = call("POST", "/cases/1/investigate",
                            {"locationKey": node["locationKey"], "action": "SEARCH"}, token=token)
        got = (result.get("data") or {}).get("unlockedClues") or []
        note(f"  调查 {node['locationKey']} -> HTTP {code}，解锁 {len(got)} 条线索")

# ---------- 2. 解全部谜题 ----------
puzzles, _ = call("GET", "/cases/1/puzzles", token=token)
solved = 0
for puzzle in puzzles.get("data") or []:
    pid = puzzle["id"]
    answer = PUZZLE_ANSWERS.get(pid)
    if answer is None:
        note(f"  谜题 {pid} 没有预置答案，跳过")
        continue
    result, code = call("POST", f"/cases/1/puzzles/{pid}", {"answer": answer}, token=token)
    data = result.get("data") or {}
    if data.get("correct"):
        solved += 1
    note(f"  谜题 {pid} {puzzle.get('puzzleKey')} -> correct={data.get('correct')} HTTP {code}")

# ---------- 3. 用关键词和 NPC 对话，解锁 NPC 线索 ----------
for npc_id, keyword in NPC_KEYWORDS:
    result, code = call("POST", "/cases/1/chat",
                        {"npcId": npc_id, "message": f"请说说{keyword}相关的情况。"}, token=token)
    data = result.get("data") or {}
    note(f"  与 NPC{npc_id} 对话（关键词「{keyword}」）-> 解锁线索 {data.get('unlockedClueIds')}")

# ---------- 4. 看当前进度 ----------
detail, _ = call("GET", "/cases/1", token=token)
d = detail.get("data") or {}
progress = d.get("progress") or {}
clues = d.get("discoveredClues") or []
note(f"进度：地点 {progress.get('investigatedLocations')}/{progress.get('totalLocations')}，"
     f"线索 {progress.get('discoveredClues')}/{progress.get('totalDiscoverableClues')}，"
     f"谜题 {progress.get('solvedPuzzles')}/{progress.get('totalPuzzles')}")
note(f"已获得线索：{[c['clueCode'] for c in clues]}")

# ---------- 5. 提交（用满档文本 + 全部已获得线索作为证据） ----------
hypothesis = ("作案者熟悉酒店供水与屋顶维护流程，利用水箱检修的时间窗口实施犯罪。"
              "当晚住客投诉供水异常，实际上是有人刻意让水泵空转，以掩盖搬运与开启屋顶舱门的声响。"
              "值班表上缺失的那十五分钟与监控时间码的扰动相互印证，说明有人事后处理过记录。")
reasoning = ("先核对住客投诉记录与水箱检修单之间的时间差，再比对值班表上缺失的那十五分钟；"
             "供水异常的时段恰好覆盖了遗体被发现之前的窗口，说明有人刻意让水泵空转以掩盖声响与出入痕迹。"
             "结合大堂监控里始终没有出现的那个身影，可以排除外部人员临时起意的可能，"
             "嫌疑因此收拢到熟悉设备维护流程的内部人员身上。")
conclusion = ("结论：作案者是熟悉酒店供水系统的内部人员，利用水箱检修窗口实施并伪装成设备意外；"
              "其行为同时解释了供水异常、监控时间码扰动与值班记录缺失三个独立疑点。")
timeline = ("2013-01-26 入住；02-01 前后最后被看见；当晚住客投诉供水异常；"
            "维护人员检查屋顶水箱并发现遗体；随后封锁现场并清点住客名单。")

submit_body = {
    "hypothesis": hypothesis,
    "keyPeople": "值班工程师、前台主管、屋顶维护人员",
    "keyTimeline": timeline,
    "evidenceClueIds": [c["id"] for c in clues],
    "reasoningText": reasoning,
    "conclusion": conclusion,
}
submit, code = call("POST", "/cases/1/submit", submit_body, token=token)
s = submit.get("data") or {}

score = s.get("totalScore")
exp_reward = s.get("expReward")
coin_reward = s.get("coinReward")
expected_exp = score * 2 + (60 if score >= 80 else 0)
expected_coins = score + (30 if score >= 80 else 0)

note("")
note(f"结案得分 {score}/100（{s.get('rating')}）")
note(f"  分项 {s.get('investigationScore')}/30 + {s.get('clueScore')}/25 "
     f"+ {s.get('timelineScore')}/20 + {s.get('logicScore')}/25")
note(f"  实际奖励 EXP +{exp_reward} / Coins +{coin_reward}")
note(f"  期望奖励 EXP +{expected_exp} / Coins +{expected_coins}")
note(f"  score>=80 加成分支是否被走到：{score >= 80}")
note(f"  奖励是否与公式一致：{exp_reward == expected_exp and coin_reward == expected_coins}")

# 再核对用户档案里的总量
profile, _ = call("GET", "/user/profile", token=token)
p = profile.get("data") or {}
note(f"  档案：EXP {p.get('exp')} / Coins {p.get('coins')} / 结案 {p.get('completedCases')} 件")

with open(REPORT, "w", encoding="utf-8") as handle:
    json.dump({
        "username": name,
        "log": log,
        "score": score,
        "expReward": exp_reward,
        "coinReward": coin_reward,
        "expectedExp": expected_exp,
        "expectedCoins": expected_coins,
        "bonusBranchExercised": bool(score is not None and score >= 80),
        "rewardMatchesFormula": exp_reward == expected_exp and coin_reward == expected_coins,
        "profile": {"exp": p.get("exp"), "coins": p.get("coins"),
                    "completedCases": p.get("completedCases")},
    }, handle, ensure_ascii=False, indent=2)
print("\n报告已写入", REPORT, flush=True)
