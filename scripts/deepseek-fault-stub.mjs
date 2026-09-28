// 故障注入桩 —— 用于验证「AI 异常时后端的降级路径」，仅用于诊断，不参与产品逻辑。
//
// 与 deepseek-stub.mjs 的区别：那个只制造「延迟窗口」，这个制造**故障**，
// 用来回答两个问题：
//   1. AI 返回的 JSON 解析失败时，接口会不会 500？（应降级，不 500）
//   2. AI 调用失败时，游戏进度会不会丢？（不应丢）
//
// 用法：
//   node scripts/deepseek-fault-stub.mjs
//   后端指向它：DEEPSEEK_BASE_URL=http://127.0.0.1:9099 DEEPSEEK_API_KEY=stub-local
//
// 模式由**文件**决定（默认 .runtime/stub-mode.txt），改文件即可切换，无需重启桩或后端：
//   echo malformed > .runtime/stub-mode.txt   # 返回畸形 JSON（模拟 JSON 被截断）
//   echo error500  > .runtime/stub-mode.txt   # 返回 HTTP 500
//   echo error401  > .runtime/stub-mode.txt   # 返回 HTTP 401（Key 无效）
//   echo error402  > .runtime/stub-mode.txt   # 返回 HTTP 402（余额不足）
//   echo error429  > .runtime/stub-mode.txt   # 返回 HTTP 429（频率限制）
//   echo empty     > .runtime/stub-mode.txt   # HTTP 200 但 content 为空
//   echo truncate  > .runtime/stub-mode.txt   # HTTP 200 且 finish_reason=length（截断告警）
//   echo refused   > .runtime/stub-mode.txt   # 直接断开连接（模拟网络不可达）
//   echo normal    > .runtime/stub-mode.txt   # 正常返回
//
// 前 7 种模式分别对应 DeepSeekService 里一个分支 —— 这些分支靠正常调用走不到，
// 只能靠故障注入覆盖。
//
// 环境变量：STUB_PORT（默认 9099）、STUB_MODE_FILE（默认 .runtime/stub-mode.txt）
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';

const PORT = Number(process.env.STUB_PORT || 9099);
const MODE_FILE = process.env.STUB_MODE_FILE
  || path.resolve(process.cwd(), '.runtime', 'stub-mode.txt');
let seq = 0;

// 需要返回 HTTP 错误码的模式
const HTTP_ERROR_MODES = { error401: 401, error402: 402, error429: 429, error500: 500 };

function currentMode() {
  try {
    return fs.readFileSync(MODE_FILE, 'utf8').trim() || 'malformed';
  } catch {
    return 'malformed';
  }
}

const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', (chunk) => { body += chunk; });
  req.on('end', () => {
    const mode = currentMode();
    let jsonMode = false;
    try { jsonMode = Boolean(JSON.parse(body || '{}').response_format); } catch { /* 忽略 */ }

    // 直接断开：模拟网络层失败，后端应捕获为「无法连接 DeepSeek API」
    if (mode === 'refused') {
      console.log(`[fault-stub] #${++seq} mode=refused 直接断开`);
      req.socket.destroy();
      return;
    }

    // 各类 HTTP 错误码：后端应映射成对应的中文原因
    const errorStatus = HTTP_ERROR_MODES[mode];
    if (errorStatus) {
      console.log(`[fault-stub] #${++seq} mode=${mode} HTTP ${errorStatus}`);
      res.writeHead(errorStatus, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: `simulated ${errorStatus}` } }));
      return;
    }

    // malformed：HTTP 200，但 content 不是合法 JSON（模拟 JSON 被 max_tokens 截断）
    // empty：HTTP 200 但 content 为空串（后端应判为「返回了空内容」）
    // truncate：HTTP 200 且 finish_reason=length（后端应打印截断告警，但仍返回内容）
    let content;
    if (mode === 'malformed') {
      content = '{"summary":"这是被截断的 JSON，缺少结尾引号与右花括号';
    } else if (mode === 'empty') {
      content = '';
    } else if (jsonMode) {
      content = JSON.stringify({
        summary: '【故障桩】正常 JSON 占位。',
        supportingEvidence: ['桩数据'],
        contradictions: [],
        missingEvidence: [],
        suggestions: [],
        confidence: 50,
        realityNotice: '【现实案件资料】游戏推理不代表现实案件结论。'
      });
    } else {
      content = '【故障桩】NPC 回复占位内容。';
    }

    const finishReason = mode === 'truncate' ? 'length' : 'stop';
    const payload = {
      id: 'fault-stub-' + (++seq),
      object: 'chat.completion',
      model: 'stub',
      choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: finishReason }]
    };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(payload));
    console.log(`[fault-stub] #${seq} mode=${mode} jsonMode=${jsonMode} finish_reason=${finishReason}`);
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[fault-stub] listening on 127.0.0.1:${PORT}, mode file=${MODE_FILE}`);
  console.log(`[fault-stub] 当前模式=${currentMode()}`);
});
