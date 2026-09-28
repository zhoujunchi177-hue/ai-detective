// 本地 DeepSeek 桩服务 —— 仅用于诊断，不参与产品逻辑。
//
// 用途：制造一个「延迟可控」的 AI 调用窗口。真实 AI 只回几百毫秒到几秒，
// 用桩把窗口拉到 5 秒以上，才能稳定观测「AI 调用期间数据库连接是否被占住」。
//
// 用法：
//   node scripts/deepseek-stub.mjs                       # 默认 127.0.0.1:9099，延迟 5 秒
//   STUB_PORT=9099 STUB_DELAY_MS=8000 node scripts/deepseek-stub.mjs
//
// 然后把后端指向它（API Key 填任意非空值即可，桩不校验）：
//   DEEPSEEK_BASE_URL=http://127.0.0.1:9099 DEEPSEEK_API_KEY=stub-local
//
// 配合 scripts/check-trx-boundary.sh 使用。
import http from 'node:http';

const PORT = Number(process.env.STUB_PORT || 9099);
const DELAY = Number(process.env.STUB_DELAY_MS || 5000);
let seq = 0;

const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', (chunk) => { body += chunk; });
  req.on('end', () => {
    // jsonMode 时后端会带 response_format，桩据此返回 JSON 内容，让正常流程走得通。
    let jsonMode = false;
    try { jsonMode = Boolean(JSON.parse(body || '{}').response_format); } catch { /* 忽略 */ }

    setTimeout(() => {
      const content = jsonMode
        ? JSON.stringify({
            summary: '【本地桩】结案分析占位内容，仅用于连接占用诊断。',
            supportingEvidence: ['桩数据'],
            contradictions: [],
            missingEvidence: [],
            suggestions: [],
            confidence: 50,
            realityNotice: '【现实案件资料】游戏推理不代表现实案件结论。'
          })
        : '【本地桩】NPC 回复占位内容，仅用于连接占用诊断。';
      const payload = {
        id: 'stub-' + (++seq),
        object: 'chat.completion',
        model: 'stub',
        choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }]
      };
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(payload));
      console.log(`[stub] #${seq} ${req.method} ${req.url} jsonMode=${jsonMode} 已延迟 ${DELAY}ms 后返回`);
    }, DELAY);
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[stub] listening on 127.0.0.1:${PORT}, delay=${DELAY}ms`);
});
