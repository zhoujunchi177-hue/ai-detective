"""带真实 DeepSeek Key 启动后端，**Key 全程不落盘**。

用途：本机开发/验收时，`DEEPSEEK_API_KEY` 通常配在 Windows 的「用户环境变量」里，
但 WorkBuddy/终端进程往往早于该变量设置而启动，**子进程继承不到**它
（`/api/health` 会返回 `deepSeekConfigured:false`）。
本脚本用 Python 的 `winreg` 直接读注册表，把 Key 注入**子进程环境变量**，
既不需要 `reg.exe`（可能被沙箱拦截），也不会把 Key 写进任何文件、日志或命令行。

用法：
    python scripts/start-backend-with-key.py

可选环境变量：
    OVERRIDE_API_KEY   用指定的 Key 覆盖注册表（用于指向本地桩，如 stub-local）
    OVERRIDE_BASE_URL  覆盖 base-url（如 http://127.0.0.1:9099 指向桩服务）

注意：本进程会一直等待子进程，因此**必须作为后台任务运行**；
前台命令一结束，沙箱就会回收这个子进程。

Windows 下的路径/端口默认值写在下面的常量里，按需修改。
"""
import os
import subprocess
import sys
import winreg

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKEND = os.path.join(REPO, "backend")
LOG = os.path.join(REPO, ".runtime", "backend.log")
MVN = os.environ.get("MVN_CMD") or "mvnw.cmd"


def read_user_env(name, default=None):
    """读 Windows 用户级环境变量（不依赖 reg.exe）。"""
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
            value, _ = winreg.QueryValueEx(key, name)
            return value
    except FileNotFoundError:
        return default


def main():
    api_key = os.environ.get("OVERRIDE_API_KEY") or read_user_env("DEEPSEEK_API_KEY")
    if not api_key:
        print("FAIL: HKCU\\Environment 里没有 DEEPSEEK_API_KEY，也没有传 OVERRIDE_API_KEY")
        return 2

    env = dict(os.environ)
    env["DEEPSEEK_API_KEY"] = api_key
    env["DEEPSEEK_MODEL"] = read_user_env("DEEPSEEK_MODEL") or "deepseek-flash"
    if os.environ.get("OVERRIDE_BASE_URL"):
        env["DEEPSEEK_BASE_URL"] = os.environ["OVERRIDE_BASE_URL"]

    env.setdefault("JAVA_HOME", r"D:\jdk")
    env.setdefault("SERVER_PORT", "8080")
    env.setdefault("DB_URL", "jdbc:mysql://127.0.0.1:3307/mindtrace?useUnicode=true"
                            "&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                            "&allowPublicKeyRetrieval=true&useSSL=false")
    env.setdefault("DB_USERNAME", "root")
    env.setdefault("DB_PASSWORD", "")

    # 只打印长度，绝不打印内容
    print(f"key length = {len(api_key)} (内容不打印)")
    print(f"model = {env['DEEPSEEK_MODEL']}")
    print(f"base_url = {env.get('DEEPSEEK_BASE_URL', '(默认 https://api.deepseek.com)')}")

    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    log_handle = open(LOG, "wb")
    command = f'"{MVN}" -o spring-boot:run'
    print(f"启动：{command}  (cwd={BACKEND})")
    process = subprocess.Popen(command, cwd=BACKEND, env=env, shell=True,
                               stdout=log_handle, stderr=subprocess.STDOUT)
    print(f"pid = {process.pid}，日志 = {LOG}")
    try:
        process.wait()
    except KeyboardInterrupt:
        process.terminate()
    finally:
        log_handle.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
