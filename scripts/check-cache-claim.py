"""检验「某处改动是否修复了前缀缓存」这类推断 —— 把猜测变成可证伪的预言。

为什么需要它：2026-09-21 我在 `ContextBuilder` 里发现「玩家提问被发了两次」
（历史最后一条 + 追加的 playerQuestion），于是推断「重复项打断了缓存前缀，
删掉它就能让本轮输入成为下一轮输入的前缀」，并**把这个推断写进了代码注释**。
推断听起来很顺，但它是错的。跑这个脚本 8 轮只对了 2 轮，于是把假注释删掉了。

它检验的预言（推断成立时应当观察到的）：
    第 N+1 轮的 cacheHit ≈ floor(第 N 轮 prompt / 128) × 128

这个 128 不是猜的：实测 cacheHit 恒为 128 的整数倍（768/896/1024/1152/1280），
说明上游的缓存是按 128 token 量化的。

它同时会打印 system+上下文 的字符数，用来回答另一个问题：
「缓存到底覆盖了哪一段？」—— 实测命中量基本就等于 system + 注入上下文，
**历史部分根本没进缓存**，所以「历史越长、缓存越省」的直觉在长会话里不成立。

⚠️ 这是**诊断工具，不是验收门槛**。它不会「通过/失败」，只把数据摆出来让你判断。
   前缀缓存本身是 best-effort（上游按需构建、几小时~几天后清除），
   单次运行的命中量会受「上一轮请求间隔太短、缓存还没建好」影响。

用法：
    python scripts/check-cache-claim.py                       # 默认读 .runtime/token-meter.jsonl
    python scripts/check-cache-claim.py a.jsonl b.jsonl       # 对比多份记录
前置：先按 README「Token 计量与提示词压缩」跑出计量记录。
"""
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT = os.path.join(REPO, ".runtime", "token-meter.jsonl")

# 上游缓存按 128 token 量化（实测 cacheHit 恒为 128 的整数倍）。
CACHE_QUANTUM = 128


def load(path):
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            row = json.loads(line)
            # 只保留真实调用且有 usage 的；reasoning 链路用的是另一套提示词，混进来会污染对比
            if row.get("usage") and row.get("kind") != "reasoning":
                rows.append(row)
    return rows


def report(path):
    rows = load(path)
    if not rows:
        print(f"{path}：没有可用的记录（先按 README 跑一次计量）")
        return

    print("=" * 92)
    print(f"{os.path.basename(path)}（{len(rows)} 轮）")
    print("=" * 92)
    print(f"{'轮':>3} {'prompt':>7} {'hit':>6} {'miss':>6} | "
          f"{'上一轮':>7} {'/128 后':>8} {'预言 hit':>9} {'实际 hit':>9} {'对?':>4}")
    print("-" * 92)

    agree = 0
    compared = 0
    quantum_ok = True
    for index, row in enumerate(rows, start=1):
        usage = row["usage"]
        hit, miss = usage.get("cacheHit", 0), usage.get("cacheMiss", 0)
        if hit % CACHE_QUANTUM:
            quantum_ok = False
        if index == 1:
            print(f"{index:>3} {usage['prompt']:>7} {hit:>6} {miss:>6} | "
                  f"{'(首轮)':>7} {'-':>8} {'-':>9} {hit:>9} {'-':>4}")
            continue
        previous = rows[index - 2]["usage"]["prompt"]
        predicted = previous // CACHE_QUANTUM * CACHE_QUANTUM
        compared += 1
        same = hit == predicted
        agree += 1 if same else 0
        print(f"{index:>3} {usage['prompt']:>7} {hit:>6} {miss:>6} | "
              f"{previous:>7} {predicted:>8} {predicted:>9} {hit:>9} {'OK' if same else 'NO':>4}")

    print("-" * 92)
    print(f"预言成立 {agree}/{compared} 轮"
          + ("  → 推断不成立（前缀并没有被修好）" if compared and agree < compared else ""))

    system_chars = rows[0].get("system", {}).get("chars", 0)
    context_chars = rows[0].get("context", {}).get("chars", 0)
    hits = [row["usage"].get("cacheHit", 0) for row in rows]
    print(f"cacheHit 是否恒为 {CACHE_QUANTUM} 的整数倍：{quantum_ok}"
          f"（观测值 {sorted(set(hits))}）")
    print(f"system+上下文 = {system_chars + context_chars} 字符"
          f"（system {system_chars} + 上下文 {context_chars}），"
          f"首轮 prompt = {rows[0]['usage']['prompt']}，"
          f"命中中位数 = {sorted(hits)[len(hits) // 2]}")
    print("  若命中中位数 ≈ system+上下文，说明**历史部分没进缓存** —— "
          "「历史越长、缓存越省」在这里不成立。")
    print()


if __name__ == "__main__":
    paths = sys.argv[1:] or [DEFAULT]
    for item in paths:
        full = item if os.path.isabs(item) else os.path.join(REPO, item)
        if os.path.exists(full):
            report(full)
        else:
            print(f"跳过（不存在）：{full}")
