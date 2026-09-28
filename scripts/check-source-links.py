"""检查「真实资料」来源链接的可达性。

为什么要专门做这个：游戏把来源链接当作「真实资料」展示给玩家，
但这些链接会失效、被反爬拦截，或在特定网络下不可达。
链接失效是**内容 bug**（不是代码 bug），必须能被定期发现。

⚠️ 必须查**三张表**：source_url 同时存在于
   case_sources（「文件」标签页）/ clues（「线索」标签页）/ case_timeline（「时间线」标签页）。
   只查 case_sources 会漏掉大部分引用 —— 曾经就漏过。

⚠️ 状态必须分三类，不能把「网络不可达」当成「死链」：
   dead        404/410 —— URL 真的不存在了。**只有这类才判失败。**
   blocked     401/403/429 —— 多半是反爬（如 Cloudflare 挑战）。真实浏览器能过，
               无头浏览器/脚本过不了。本机实测 fbi.gov 就是这种。
   unreachable 连接超时/重置 —— 本机网络限制。实测 latimes.com / bbc.com 在国内网络
               就是这种：站点本身活着，只是这里到不了。
   把后两类判失败会制造假警报，让人开始忽略这个脚本 —— 那比不做检查更糟。

用法：python scripts/check-source-links.py
退出码：0 无死链；1 存在 404/410 死链（会列出）
"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPORT = os.path.join(REPO, ".runtime", "source-links-report.json")

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

# 三张表都要查。列名统一是 source_name / source_url。
TABLES = ("case_sources", "clues", "case_timeline")

# 明确「不存在」的状态码 —— 只有这些判失败。
DEAD_CODES = {404, 410}
# 多半是反爬拦截的状态码 —— 真实浏览器可能正常，不判失败。
BLOCKED_CODES = {401, 403, 429}


def fetch_sources():
    """按 URL 去重聚合，同时记录它被哪些表/条目引用（便于定位改动点）。"""
    union = " UNION ALL ".join(
        f"SELECT '{t}' AS tbl, id, source_name, source_url FROM mindtrace.{t} "
        f"WHERE source_url IS NOT NULL AND source_url <> ''" for t in TABLES)
    proc = subprocess.run(
        [MYSQL, "-h127.0.0.1", "-P3307", "-uroot", "--default-character-set=utf8mb4",
         "-N", "-B", "-e", union + ";"],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError("查询数据库失败：" + (proc.stderr or "")[:300])

    grouped = {}
    for line in proc.stdout.strip().splitlines():
        parts = line.split("\t")
        if len(parts) < 4:
            continue
        table, row_id, name, url = parts[0], parts[1], parts[2], parts[3]
        item = grouped.setdefault(url, {"url": url, "refs": [], "names": set()})
        item["refs"].append(f"{table}#{row_id}")
        item["names"].add(name)
    for item in grouped.values():
        item["names"] = sorted(item["names"])
        item["count"] = len(item["refs"])
    return sorted(grouped.values(), key=lambda x: -x["count"])



def check(url, timeout=20):
    """返回 (状态, 说明)。状态：ok / dead / blocked / unreachable"""
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            code = resp.status
            final = resp.geturl()
            if code < 400:
                return "ok", f"HTTP {code}" + (f"（跳转到 {final}）" if final != url else "")
            if code in DEAD_CODES:
                return "dead", f"HTTP {code} 页面不存在"
            return "blocked", f"HTTP {code}（可能是反爬拦截，真实浏览器或可正常访问）"
    except urllib.error.HTTPError as exc:
        if exc.code in DEAD_CODES:
            return "dead", f"HTTP {exc.code} 页面不存在"
        if exc.code in BLOCKED_CODES:
            return "blocked", f"HTTP {exc.code}（可能是反爬拦截，真实浏览器或可正常访问）"
        return "blocked", f"HTTP {exc.code}"
    except Exception as exc:  # noqa: BLE001
        return "unreachable", f"{type(exc).__name__}（本机网络到不了，不代表 URL 失效）"


MARKS = {"ok": "OK  ", "dead": "DEAD", "blocked": "WARN", "unreachable": "WARN"}


def main():
    sources = fetch_sources()
    total_refs = sum(item["count"] for item in sources)
    print(f"去重后 {len(sources)} 个链接，共 {total_refs} 处引用"
          f"（{'/'.join(TABLES)}）\n", flush=True)

    results = []
    counts = {"ok": 0, "dead": 0, "blocked": 0, "unreachable": 0}
    for item in sources:
        status, detail = check(item["url"])
        counts[status] += 1
        print(f"{MARKS[status]} x{item['count']:<3} {detail:<52} {item['url']}", flush=True)
        print(f"     来源标签: {', '.join(item['names'])}", flush=True)
        print(f"     引用位置: {', '.join(item['refs'][:8])}"
              + (" ..." if item["count"] > 8 else ""), flush=True)
        results.append({**item, "status": status, "detail": detail})

    os.makedirs(os.path.dirname(REPORT), exist_ok=True)
    with open(REPORT, "w", encoding="utf-8") as handle:
        json.dump({"total": len(results), "totalRefs": total_refs, "counts": counts,
                   "results": results}, handle, ensure_ascii=False, indent=2)

    # 自检：每个去重后的链接都必须恰好被判定一次，否则统计和退出码都不可信。
    if sum(counts.values()) != len(results):
        print(f"\n!! 自检失败：判定数 {sum(counts.values())} != 链接数 {len(results)}")
        return 1

    print(f"\nSUMMARY links={len(results)} refs={total_refs} ok={counts['ok']} "
          f"dead={counts['dead']} blocked={counts['blocked']} unreachable={counts['unreachable']}")
    print("报告已写入", REPORT)

    if counts["blocked"] or counts["unreachable"]:
        print("\n以下为**警告，不算失败**：")
        if counts["blocked"]:
            print(f"  - {counts['blocked']} 条被反爬拦截（4xx）。真实浏览器通常能打开，")
            print("    判定方法：用有头浏览器访问一次，或换个网络再试。")
        if counts["unreachable"]:
            print(f"  - {counts['unreachable']} 条本机网络不可达。实测 latimes.com / bbc.com")
            print("    在国内网络即如此：站点活着，只是这里连不上。换 URL 前务必先用")
            print("    别的渠道（如搜索引擎索引、Wayback）确认目标页是否真的不存在。")

    if counts["dead"]:
        print("\n存在 404/410 死链，属于**内容问题**（database/data.sql），不是代码 bug。")
        print("修复时要同步 database/data.sql 与 database/migration-*.sql，")
        print("否则「全新安装」与「老库升级」会分叉。")
        print("换了机构的，来源标签/类型/可靠性必须一起改 —— 否则等于伪造来源。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
