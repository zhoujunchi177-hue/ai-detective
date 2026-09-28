"""验证「全新安装」与「老库升级」不会分叉。

背景：`data.sql` 只管全新安装，老库不会重跑它；老库的改动靠 `migration-*.sql`。
两条路径只要有一处没对齐（比如迁移漏改了某个内容字段），
就会出现「新装用户看到的和升级用户看到的不是同一份数据」——
本项目踩过一次：只迁移了 rarity，忘了 description。

做法：把 schema.sql + data.sql 装进一个**独立库**，然后逐表逐行与当前库比对。

⚠️ 安全设计（很重要）：
   `data.sql` 带 `TRUNCATE TABLE`，且两个脚本都硬编码 `USE mindtrace;`。
   替换没生效就直接执行 = 清空正在使用的库。
   所以生成临时副本后会把目标库名抹掉，断言正文里**一个 `mindtrace` 都不剩**，
   否则立刻失败退出、不产出任何文件。

用法：python scripts/verify-fresh-install.py
      python scripts/verify-fresh-install.py --self-test   # 故障注入自检
退出码：0 两条路径一致；1 有分叉（会列出差异）
"""
import os
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"
SCRATCH = "mindtrace_fresh"

# 这些表存的是**用户产生**的数据，新库天然为空，不参与比对。
USER_TABLES = {
    "users", "game_records", "chat_messages", "user_clues", "user_puzzles",
    "user_achievements", "leaderboard_entries", "investigation_records",
    "evidence_relationships",
}

SOURCES = [("database/schema.sql", ".runtime/fresh-schema.sql"),
           ("database/data.sql", ".runtime/fresh-data.sql")]

# 这些列记录的是「这一行什么时候写进来的」，新装和升级天然不同，
# 不参与比对 —— 否则每张表都会被误报成「分叉」（踩过这个假阳性）。
EXCLUDE_COLUMNS = {"created_at", "updated_at"}


def mysql(sql, database=None, stdin_file=None):
    args = [MYSQL, "-h127.0.0.1", "-P3307", "-uroot", "--default-character-set=utf8mb4",
            "-N", "-B"]
    if database:
        args += ["-D", database]
    if stdin_file:
        with open(stdin_file, "rb") as handle:
            proc = subprocess.run(args, stdin=handle, capture_output=True)
        return proc
    args += ["-e", sql]
    return subprocess.run(args, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")


def make_copies():
    """生成指向独立库的临时副本，带硬性防呆。"""
    os.makedirs(os.path.join(REPO, ".runtime"), exist_ok=True)
    for src_name, dst_rel in SOURCES:
        src = os.path.join(REPO, src_name)
        dst = os.path.join(REPO, dst_rel)
        with open(src, encoding="utf-8") as handle:
            text = handle.read()
        text = text.replace("CREATE DATABASE IF NOT EXISTS mindtrace",
                            f"CREATE DATABASE IF NOT EXISTS {SCRATCH}")
        text = text.replace("USE mindtrace;", f"USE {SCRATCH};")

        # 防呆：抹掉目标库名后正文不能残留 mindtrace
        if "mindtrace" in text.replace(SCRATCH, ""):
            print(f"!! 防呆拦截：{src_name} 替换后仍残留 mindtrace，拒绝继续")
            for line_no, line in enumerate(text.splitlines(), 1):
                if "mindtrace" in line.replace(SCRATCH, ""):
                    print(f"   行 {line_no}: {line}")
            return False
        if f"USE {SCRATCH};" not in text:
            print(f"!! 防呆拦截：{src_name} 未指向 {SCRATCH}")
            return False
        with open(dst, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)
    print(f"临时副本已生成（均指向独立库 {SCRATCH}），防呆检查通过")
    return True


def table_list(database):
    proc = mysql("SHOW TABLES;", database=database)
    if proc.returncode != 0:
        raise RuntimeError(f"读取 {database} 的表失败：" + (proc.stderr or "")[:200])
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def columns_of(database, table):
    sql = ("SELECT column_name FROM information_schema.columns "
           f"WHERE table_schema='{database}' AND table_name='{table}' "
           "ORDER BY ordinal_position;")
    proc = mysql(sql)
    if proc.returncode != 0:
        return []
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def dump_table(database, table):
    """整表导出（排除插入时间列），按第一列排序保证可比。返回 (行列表, 列名, 错误)。"""
    keep = [c for c in columns_of(database, table) if c not in EXCLUDE_COLUMNS]
    if not keep:
        return None, [], f"没有可比对的列（表 {table}）"
    collist = ", ".join(f"`{c}`" for c in keep)
    proc = mysql(f"SELECT {collist} FROM `{table}` ORDER BY 1;", database=database)
    if proc.returncode != 0:
        return None, keep, (proc.stderr or "").strip()[:200]
    rows = [line for line in proc.stdout.splitlines() if line.strip()]
    return rows, keep, None


def describe_row_diff(fresh_rows, live_rows, columns):
    """行数相同时，找出具体是哪几列不一样 —— 输出要能直接指向要改的字段。"""
    notes = []
    for index, (fresh, live) in enumerate(zip(fresh_rows, live_rows)):
        if fresh == live:
            continue
        fresh_cells = fresh.split("\t")
        live_cells = live.split("\t")
        if len(fresh_cells) != len(live_cells):
            notes.append(f"第 {index + 1} 行列数不同")
            continue
        changed = [columns[i] for i, (a, b) in enumerate(zip(fresh_cells, live_cells))
                   if a != b and i < len(columns)]
        notes.append(f"第 {index + 1} 行：列 [{', '.join(changed)}] 不同")
        if len(notes) >= 3:
            break
    return notes


def main():
    self_test = "--self-test" in sys.argv
    if not make_copies():
        return 1

    # 装到独立库
    for _src, dst_rel in SOURCES:
        proc = mysql("", stdin_file=os.path.join(REPO, dst_rel))
        if proc.returncode != 0:
            print(f"!! 执行 {dst_rel} 失败：")
            print((proc.stderr or b"").decode("utf-8", "replace")[:500])
            return 1
    print(f"已装好全新库 {SCRATCH}")
    print(f"比对时已排除插入时间列：{', '.join(sorted(EXCLUDE_COLUMNS))}\n")

    try:
        if self_test:
            # 故障注入：往全新库改一处内容，比对**必须**能发现。
            # 「全绿」本身不能证明比对有效 —— 万一是空转呢。
            print("!! 自检模式：往全新库注入一处差异（case_sources #1 的 description）")
            mysql("UPDATE case_sources SET description = '（自检注入的差异）' WHERE id = 1;",
                  database=SCRATCH)
            print()

        fresh_tables = set(table_list(SCRATCH))
        live_tables = set(table_list("mindtrace"))
        shared = sorted((fresh_tables & live_tables) - USER_TABLES)

        print(f"=== 逐表比对（共 {len(shared)} 张内容表，已排除 {len(USER_TABLES)} 张用户数据表）===")
        diffs = []
        for table in shared:
            fresh_rows, columns, fresh_err = dump_table(SCRATCH, table)
            live_rows, _live_columns, live_err = dump_table("mindtrace", table)
            if fresh_err or live_err:
                diffs.append((table, f"读取失败：{fresh_err or live_err}"))
                print(f"  ??   {table}：读取失败")
                continue
            if fresh_rows == live_rows:
                print(f"  OK   {table}（{len(fresh_rows)} 行 × {len(columns)} 列）")
                continue

            only_fresh = [r for r in fresh_rows if r not in live_rows]
            only_live = [r for r in live_rows if r not in fresh_rows]
            print(f"  DIFF {table}：全新库 {len(fresh_rows)} 行 / 当前库 {len(live_rows)} 行"
                  f"（单边独有 {len(only_fresh)} / {len(only_live)}）")
            if len(fresh_rows) == len(live_rows):
                for note in describe_row_diff(fresh_rows, live_rows, columns):
                    print(f"       {note}")
                diffs.append((table, "内容列不同（行数一致）"))
            else:
                for row in only_fresh[:2]:
                    print(f"       仅在全新库：{row[:130]}")
                for row in only_live[:2]:
                    print(f"       仅在当前库：{row[:130]}")
                diffs.append((table, f"行数不同 {len(fresh_rows)} vs {len(live_rows)}"))

        # 只在一边存在的表也算分叉
        only_in_fresh = sorted(fresh_tables - live_tables - USER_TABLES)
        only_in_live = sorted(live_tables - fresh_tables - USER_TABLES)
        if only_in_fresh or only_in_live:
            print("\n!! 表结构本身分叉：")
            if only_in_fresh:
                print("   仅全新库有：" + ", ".join(only_in_fresh))
            if only_in_live:
                print("   仅当前库有：" + ", ".join(only_in_live))
            diffs.append(("表结构", "存在单边独有的表"))

        print(f"\nSUMMARY 比对表数={len(shared)} 分叉={len(diffs)}")

        if self_test:
            found = any(table == "case_sources" for table, _detail in diffs)
            if found:
                print("\n✅ 自检通过：注入的差异被正确发现，说明比对逻辑不是空转")
                return 0
            print("\n!! 自检失败：注入了差异却报「无分叉」—— 比对逻辑是空转的")
            return 1

        if diffs:
            print("\n分叉明细：")
            for table, detail in diffs:
                print(f"  - {table}：{detail}")
            print("\n修法：把缺的改动补进 database/migration-*.sql（幂等），")
            print("      或把 database/data.sql 里对应的内容字段一起改掉 —— 两边必须同时动。")
            return 1
        print("✅ 全新安装与老库升级完全一致，没有分叉")
        return 0
    finally:
        mysql(f"DROP DATABASE IF EXISTS {SCRATCH};")
        print(f"\n已清理临时库 {SCRATCH}")
        # 临时 SQL 也删掉：它们带 TRUNCATE，留一份在磁盘上没有好处
        for _src, dst_rel in SOURCES:
            path = os.path.join(REPO, dst_rel)
            if os.path.exists(path):
                os.remove(path)
        print("已清理临时 SQL 副本")


if __name__ == "__main__":
    sys.exit(main())
