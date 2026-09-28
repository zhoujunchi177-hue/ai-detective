"""探测上游 DeepSeek 接口是否支持「关闭思考」参数。

背景：实测 NPC 每轮 completion 540 token 里有 301 是 reasoning_tokens（55.7%），
而 DeepSeekService 的请求体里**没有**任何思考开关。第三方文档说 `enable_thinking`
（bool）可以控制，且 `deepseek-v4-flash` 默认不思考。但本项目实测是**在思考**的，
所以必须**直接问上游**，不能照抄文档。

本脚本发 3 个最小请求，只打印 token 计数，**绝不打印 Key**：
  A. 基线：不带任何思考参数        → 看默认是否思考
  B. enable_thinking: false        → 看是否被接受、思考是否归零
  C. thinking: {"type":"disabled"} → pro 模型上文档说的替代写法

用法：
    python scripts/probe-thinking-param.py

退出码：0 表示至少有一个「关闭思考」写法生效。
"""
import json
import os
import sys
import urllib.request
import urllib.error

try:
    import winreg
except ImportError:  # 非 Windows
    winreg = None

BASE_URL = os.environ.get("PROBE_BASE_URL", "https://api.deepseek.com")
PATH = "/chat/completions"

MESSAGES = [
    {"role": "system", "content": "你是侦探游戏里的证人，用口语回答，最多两句。"},
    {"role": "user", "content": "昨晚十点你在哪？"},
]


def read_user_env(name, default=None):
    """读 Windows 用户级环境变量（不依赖 reg.exe）。"""
    if winreg is None:
        return default
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
            value, _ = winreg.QueryValueEx(key, name)
            return value
    except FileNotFoundError:
        return default


def call(api_key, model, extra, label, messages=None):
    payload = {"model": model, "messages": messages or MESSAGES, "max_tokens": 1200,
               "temperature": 0.55}
    payload.update(extra)
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + PATH, data=data,
        headers={"Authorization": "Bearer " + api_key,
                 "Content-Type": "application/json"})
    print(f"\n=== {label} ===  额外参数: {extra or '（无）'}")
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[:400]
        print(f"  HTTP {exc.code}  被拒绝 -> {detail}")
        return None
    except Exception as exc:  # noqa: BLE001
        print(f"  请求失败: {type(exc).__name__}: {exc}")
        return None

    usage = body.get("usage", {}) or {}
    details = usage.get("completion_tokens_details", {}) or {}
    message = (body.get("choices") or [{}])[0].get("message", {}) or {}
    content = message.get("content") or ""
    reasoning_field = message.get("reasoning_content") or ""
    print(f"  completion={usage.get('completion_tokens')}"
          f"  reasoning_tokens={details.get('reasoning_tokens')}"
          f"  reasoning_content={len(reasoning_field)} 字"
          f"  正文={len(content)} 字")
    return usage, content


def main():
    api_key = os.environ.get("DEEPSEEK_API_KEY") or read_user_env("DEEPSEEK_API_KEY")
    if not api_key:
        print("FAIL: 没有 DEEPSEEK_API_KEY（环境变量或 HKCU\\Environment 都没有）")
        return 2
    model = (os.environ.get("DEEPSEEK_MODEL")
             or read_user_env("DEEPSEEK_MODEL") or "deepseek-flash")
    print(f"key length = {len(api_key)} (内容不打印)")
    print(f"model = {model}   base_url = {BASE_URL}")

    # A/B：确认默认在思考、且 enable_thinking 无效
    call(api_key, model, {}, "A 基线（不带参数）")
    call(api_key, model, {"enable_thinking": False}, "B enable_thinking=false")

    # C：连发两次，确认「思考归零」不是偶然（单次可能碰巧不思考）
    call(api_key, model, {"thinking": {"type": "disabled"}}, "C thinking=disabled 第1次")
    call(api_key, model, {"thinking": {"type": "disabled"}}, "C thinking=disabled 第2次")

    # D：JSON 模式 + 关思考 —— 关思考后还能不能吐出合法 JSON（这是最大风险点）
    # ⚠️ DeepSeek 要求 response_format=json_object 时提示词里必须出现 "json" 字样，
    #    所以这里必须用一个真的含 "JSON" 的提示词，否则会拿到无关的 400。
    json_messages = [
        {"role": "system", "content": "你是推理审阅器，只返回 JSON 对象，字段 summary(string) "
                                      "和 confidence(number)。不要 Markdown。"},
        {"role": "user", "content": "玩家推理：凶手是管家。案件：别墅密室案。请评估一致性。"},
    ]
    json_extra = {"thinking": {"type": "disabled"},
                  "response_format": {"type": "json_object"}}
    # D0 对照：同样 JSON 模式但**保留思考**，才能算出关思考在 JSON 链路上省多少
    call(api_key, model, {"response_format": {"type": "json_object"}},
         "D0 JSON模式 + 保留思考（对照）", messages=json_messages)
    result = call(api_key, model, json_extra, "D JSON模式 + thinking=disabled",
                  messages=json_messages)

    print("\n================ 结论 ================")
    if result is not None:
        usage, content = result
        reason = (usage.get("completion_tokens_details") or {}).get("reasoning_tokens")
        print(f"JSON 模式下 thinking=disabled：reasoning_tokens={reason}")
        try:
            parsed = json.loads(content)
            print(f"  -> 返回的正文是**合法 JSON**，顶层字段：{list(parsed)[:8]}")
        except Exception as exc:  # noqa: BLE001
            print(f"  -> ⚠️ 正文**不是合法 JSON**（{type(exc).__name__}），"
                  f"前 120 字：{content[:120]!r}")
    print("关键判据：C 两次都归零 -> 参数稳定生效；D 能返回合法 JSON -> 关思考可用在 JSON 链路。")
    print("（B 被接受但无效，说明网上流传的 enable_thinking 在本模型上不可靠，别照抄。）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
