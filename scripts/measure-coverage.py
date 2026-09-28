"""跑后端单测并产出 JaCoCo 覆盖率报告，**自动定位「部分覆盖」分支**。

为什么需要它：
「哪些分支写了但从没被执行过」靠读代码猜不可靠。这个脚本把整套流程固化成一条命令，
不用每次手工改 pom、手工找行号。

核心用法是看输出里的 **「部分覆盖」行**（`mb>0 且 cb>0`）——
也就是「测试碰到了这一行，但分支没走全」。这才是纯函数单测该补的缺口。
`mb>0 且 cb==0` 的多是集成层（mapper 调用、控制器），由接口级 / E2E 验收覆盖，不必强求单测。

用法：
    python scripts/measure-coverage.py              # 测量；有「部分覆盖」行则 exit 1
    python scripts/measure-coverage.py --no-gate    # 只看报告，退出码始终 0（测量失败除外）

退出码：
    0  测量成功且没有「部分覆盖」行
    1  测量成功，但存在「部分覆盖」行（= 待补的单测缺口）
    2  测量本身失败（下不到 jar / 测试挂了 / pom 还原失败）

⚠️ 两个已踩过的坑，脚本里都处理了：
  1. `pom.xml` 里**显式写了 `<argLine>`**，它的优先级高于 `-DargLine` 属性，
     所以命令行传 `-DargLine` 会被**整个忽略**（症状：测试全过但 jacoco.exec 不生成）。
     → 只能临时改 pom。脚本用 try/finally 保证还原，并校验还原后的哈希。
  2. argLine 里的路径**不能含空格**（项目目录常带空格，如 "AI Detective"），
     否则 JVM 参数被空格切碎。→ agent 与 destfile 都放在无空格的临时目录里。

产物（都在 .runtime/ 下，已被 gitignore）：
    .runtime/jacoco/jacoco.exec / jacoco.xml / jacoco.csv / html/index.html
"""
import csv
import hashlib
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKEND = os.path.join(REPO, "backend")
POM = os.path.join(BACKEND, "pom.xml")
OUT = os.path.join(REPO, ".runtime", "jacoco")
CLASSES = os.path.join(BACKEND, "target", "classes")
SOURCES = os.path.join(BACKEND, "src", "main", "java")

JACOCO_VERSION = "0.8.15"
MAVEN_BASE = "https://repo1.maven.org/maven2/org/jacoco"
AGENT_URL = f"{MAVEN_BASE}/org.jacoco.agent/{JACOCO_VERSION}/org.jacoco.agent-{JACOCO_VERSION}-runtime.jar"
CLI_URL = f"{MAVEN_BASE}/org.jacoco.cli/{JACOCO_VERSION}/org.jacoco.cli-{JACOCO_VERSION}-nodeps.jar"

# 纯函数所在的类（这些类里出现「部分覆盖」行才是真的单测缺口）
PURE_FUNCTION_FILES = {
    "CaseQueryService.java", "AgentService.java", "EvidenceBoardService.java",
    "AchievementService.java", "PuzzleService.java", "ReasoningService.java",
    "DeepSeekService.java",
}


def log(message):
    print(message, flush=True)


def sha256(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def spaceless_dir():
    """返回一个不含空格的临时目录（放 agent 与 destfile）。"""
    for candidate in (os.path.join(os.path.expanduser("~"), ".mindtrace-jacoco"),
                      "C:/mindtrace-jacoco"):
        if " " not in candidate:
            os.makedirs(candidate, exist_ok=True)
            return candidate
    raise RuntimeError("找不到不含空格的临时目录")


def ensure_jars():
    """确保 agent / cli 两个 jar 存在（缺则下载）。返回 (agent, cli)。"""
    os.makedirs(OUT, exist_ok=True)
    agent = os.path.join(OUT, "jacocoagent.jar")
    cli = os.path.join(OUT, "jacococli.jar")
    for path, url in ((agent, AGENT_URL), (cli, CLI_URL)):
        if os.path.exists(path) and open(path, "rb").read(2) == b"PK":
            continue
        log(f"下载 {os.path.basename(path)} ...")
        try:
            urllib.request.urlretrieve(url, path)
        except Exception as exc:  # noqa: BLE001
            raise RuntimeError(f"下载失败（需要联网）：{exc}") from exc
        if open(path, "rb").read(2) != b"PK":
            raise RuntimeError(f"{path} 不是合法 jar")
    return agent, cli


def patch_pom(agent_path, destfile):
    """把 agent 接进 pom 的 <argLine>，返回原始字节（供还原）。"""
    original = open(POM, "rb").read()
    text = original
    match = re.search(rb"<argLine>(.*?)</argLine>", text, re.S)
    if not match:
        raise RuntimeError("pom.xml 里找不到 <argLine>，无法挂 agent")
    # 关键：把现有 argLine 内容原样保留，只在末尾追加 agent
    existing = match.group(1).strip()
    agent_arg = f"-javaagent:{agent_path}=destfile={destfile}".encode()
    patched = b"<argLine>" + existing + b" " + agent_arg + b"</argLine>"
    text = text[:match.start()] + patched + text[match.end():]
    with open(POM, "wb") as handle:
        handle.write(text)
    return original


def restore_pom(original, before_hash):
    """还原 pom 并校验哈希。返回 True 表示精确还原。"""
    with open(POM, "wb") as handle:
        handle.write(original)
    after = sha256(POM)
    ok = after == before_hash
    log(f"pom.xml 还原：{'精确一致' if ok else '!! 不一致 !!'}  sha256={after[:16]}...")
    return ok


def run_tests(env):
    """跑 mvnw.cmd -o -B test。返回 (ok, 摘要行列表)。"""
    mvn = os.environ.get("MVN_CMD") or "mvnw.cmd"
    log(f"运行：{mvn} -o -B test")
    proc = subprocess.run(f'"{mvn}" -o -B test', cwd=BACKEND, env=env, shell=True,
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    tail = (proc.stdout or "").strip().splitlines()
    summary = [line for line in tail if "Tests run:" in line and "Results" not in line]
    ok = proc.returncode == 0 and any("Failures: 0, Errors: 0" in line for line in summary[-1:])
    for line in tail[-6:]:
        log("  " + line)
    return ok, summary


def run_report(java, cli, exec_file):
    """生成 xml / csv / html 三份报告。"""
    cmd = [java, "-jar", cli, "report", exec_file.replace("\\", "/"),
           "--classfiles", CLASSES, "--sourcefiles", SOURCES,
           "--xml", os.path.join(OUT, "jacoco.xml"),
           "--csv", os.path.join(OUT, "jacoco.csv"),
           "--html", os.path.join(OUT, "html")]
    log("生成报告 ...")
    proc = subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        log((proc.stdout or "") + (proc.stderr or ""))
        raise RuntimeError("jacococli 生成报告失败")


def summarize_csv():
    rows = list(csv.DictReader(open(os.path.join(OUT, "jacoco.csv"), encoding="utf-8")))
    def total(missed, covered):
        m = sum(int(r[missed]) for r in rows)
        c = sum(int(r[covered]) for r in rows)
        return m, c
    log("")
    log("=== 总体（纯单测）===")
    for label, (m, c) in {
        "指令": total("INSTRUCTION_MISSED", "INSTRUCTION_COVERED"),
        "分支": total("BRANCH_MISSED", "BRANCH_COVERED"),
        "行": total("LINE_MISSED", "LINE_COVERED"),
        "方法": total("METHOD_MISSED", "METHOD_COVERED"),
    }.items():
        pct = 100 * c / (m + c) if m + c else 0
        log(f"  {label}: {c}/{m + c} = {pct:.1f}%")
    log("  说明：本项目「纯函数单测 + 接口级/E2E 集成验证」分层，单测覆盖天然偏低，"
        "不要拿总分当结论。")


def find_partial_lines():
    """返回 [(文件, 行号, 漏分支数, 已覆盖分支数, 源码行)]，即 mb>0 且 cb>0 的行。"""
    root = ET.parse(os.path.join(OUT, "jacoco.xml")).getroot()
    found = []
    for package in root.iter("package"):
        for sourcefile in package.iter("sourcefile"):
            name = sourcefile.get("name")
            path = os.path.join(SOURCES, package.get("name"), name)
            try:
                source_lines = open(path, encoding="utf-8").read().splitlines()
            except OSError:
                source_lines = []
            for line in sourcefile.iter("line"):
                mb, cb = int(line.get("mb", 0)), int(line.get("cb", 0))
                if mb > 0 and cb > 0:
                    nr = int(line.get("nr"))
                    text = source_lines[nr - 1].strip() if nr <= len(source_lines) else ""
                    found.append((name, nr, mb, cb, text))
    return found


def main():
    gate = "--no-gate" not in sys.argv

    java_home = os.environ.get("JAVA_HOME") or r"D:\jdk"
    java = os.path.join(java_home, "bin", "java.exe")
    if not os.path.exists(java):
        log(f"FAIL: 找不到 java（JAVA_HOME={java_home}）")
        return 2

    try:
        agent, cli = ensure_jars()
    except RuntimeError as exc:
        log(f"FAIL: {exc}")
        return 2

    tmp = spaceless_dir()
    agent_nospace = os.path.join(tmp, "jacocoagent.jar")
    shutil.copyfile(agent, agent_nospace)
    exec_file = os.path.join(tmp, "jacoco.exec")
    if os.path.exists(exec_file):
        os.remove(exec_file)

    before_hash = sha256(POM)
    original = None
    try:
        original = patch_pom(agent_nospace.replace("\\", "/"), exec_file.replace("\\", "/"))
        log(f"pom.xml 已临时挂上 agent（原 sha256={before_hash[:16]}...）")

        env = dict(os.environ)
        env["JAVA_HOME"] = java_home
        ok, summary = run_tests(env)
        if not ok:
            log("FAIL: 测试未通过，先修测试再看覆盖率")
            return 2
    finally:
        # 无论成功失败都必须还原，否则会把指向 agent 的 pom 留在工作区
        if original is not None:
            if not restore_pom(original, before_hash):
                log("FAIL: pom.xml 未能精确还原，请手工 git checkout -- backend/pom.xml")
                return 2

    if not os.path.exists(exec_file):
        log("FAIL: jacoco.exec 未生成（agent 可能没生效）")
        return 2

    try:
        run_report(java, cli, exec_file)
    except RuntimeError as exc:
        log(f"FAIL: {exc}")
        return 2

    summarize_csv()

    partial = find_partial_lines()
    log("")
    log("=== 部分覆盖行（测试碰到了但分支没走全）===")
    if not partial:
        log("  无。所有被单测触及的行，分支都走全了。")
    else:
        for name, nr, mb, cb, text in sorted(partial):
            mark = "★纯函数类" if name in PURE_FUNCTION_FILES else ""
            log(f"  {name}:{nr}  漏 {mb} / 已覆盖 {cb}  {mark}")
            log(f"      {text}")

    log("")
    log(f"报告：{os.path.join(OUT, 'html', 'index.html')}")
    log(f"CSV ：{os.path.join(OUT, 'jacoco.csv')}")
    log("提示：`mb>0 且 cb==0` 的行多为集成层（mapper 调用），由接口级 / E2E 覆盖，不必强求单测。")

    if gate and partial:
        log("")
        log(f"退出码 1：存在 {len(partial)} 处部分覆盖行（待补的单测缺口）")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
