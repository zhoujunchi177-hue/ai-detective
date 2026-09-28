// Token 计量代理 —— 只用于**测量**，不参与产品逻辑。
//
// 为什么需要它：`DeepSeekService` 没有解析响应里的 `usage`，所以我们手上
// 一个真实 token 数字都没有。要优化 token 消耗，先得量准 —— 不量就优化等于猜。
//
// 做法：把后端指向本代理，代理**原样转发**到真实 DeepSeek API
// （Authorization 头照传，所以用的是后端自己的真实 Key），
// 然后把请求体和响应里的 `usage` 记下来。
//
// 用法：
//   node scripts/deepseek-meter-proxy.mjs
//   OVERRIDE_BASE_URL=http://127.0.0.1:9098 python scripts/start-backend-with-key.py
//   （只覆盖 base-url、不覆盖 api-key，这样用的是注册表里的真实 Key）
//   跑完记得切回：python scripts/start-backend-with-key.py
//
// 输出：`.runtime/token-meter.jsonl`（每次调用一行，含分角色字符数与 usage）
//       `.runtime/token-meter-summary.json`（累计汇总）
import fs from 'node:fs'
import http from 'node:http'
import https from 'node:https'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const PORT = Number(process.env.METER_PORT || 9098)
const UPSTREAM = process.env.METER_UPSTREAM || 'https://api.deepseek.com'
// ⚠️ 必须用 fileURLToPath，不能手写 `new URL(...).pathname` ——
// 后者给的是**百分号编码**形式，路径里有空格时会变成 `AI%20Detective`，
// 于是在旁边建出一个名字错误的目录（本项目路径带空格，踩过一次）。
const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const JSONL = path.join(REPO, '.runtime', 'token-meter.jsonl')
const SUMMARY = path.join(REPO, '.runtime', 'token-meter-summary.json')

fs.mkdirSync(path.dirname(JSONL), { recursive: true })

/** 统计一条消息的字符数与粗略 token 估算。
 *  中文大致 1 字 ≈ 1 token，英文/标点约 4 字符 ≈ 1 token —— 这里只做**相对比较**用，
 *  真实数字一律以响应里的 usage 为准。 */
function stat(content) {
  const text = content || ''
  const cjk = (text.match(/[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]/g) || []).length
  const other = text.length - cjk
  return { chars: text.length, estTokens: cjk + Math.ceil(other / 4) }
}

const totals = { calls: 0, promptTokens: 0, completionTokens: 0, totalTokens: 0, bytesIn: 0 }
const byKind = {}

function classify(messages) {
  // 后端实际的消息顺序（见 PromptBuilder.npcDialoguePrompt）：
  //   system  →  注入上下文(buildNpcContextText)  →  历史对话…  →  玩家问题
  // 所以「注入上下文」是**第一条非 system 消息**，「问题」是最后一条，
  // 中间那些才是真正的历史。第一版把 contextText 混进历史里，归因就错了。
  const system = messages.filter((m) => m.role === 'system')
  const rest = messages.filter((m) => m.role !== 'system')
  return { system, rest }
}

const server = http.createServer((req, res) => {
  const chunks = []
  req.on('data', (c) => chunks.push(c))
  req.on('end', () => {
    const raw = Buffer.concat(chunks).toString('utf8')
    let parsed = null
    try { parsed = JSON.parse(raw) } catch { /* 非 JSON 就原样转发 */ }

    const record = { at: new Date().toISOString(), path: req.url, bytesIn: raw.length }
    if (parsed && Array.isArray(parsed.messages)) {
      const { system, rest } = classify(parsed.messages)
      const sum = (list) => list.map((m) => stat(m.content)).reduce((a, b) => ({ chars: a.chars + b.chars, estTokens: a.estTokens + b.estTokens }), { chars: 0, estTokens: 0 })
      const sys = sum(system)
      // 第一条非 system = 注入上下文（知识边界 + 本轮要求）；最后一条 = 玩家问题；中间 = 历史
      const ctxMsg = rest.length ? stat(rest[0].content) : { chars: 0, estTokens: 0 }
      const question = rest.length > 1 ? stat(rest[rest.length - 1].content) : { chars: 0, estTokens: 0 }
      const historyList = rest.length > 2 ? rest.slice(1, -1) : []
      const hist = sum(historyList)
      record.messages = parsed.messages.length
      record.system = sys
      record.context = ctxMsg
      record.question = question
      record.history = hist
      record.historyMessages = historyList.length
      // 逐条明细：方便事后重新归因，不用再跑一遍真实调用
      record.breakdown = parsed.messages.map((m) => ({ role: m.role, chars: (m.content || '').length }))
      record.maxTokens = parsed.max_tokens
      record.jsonMode = parsed.response_format ? true : false
      record.kind = sys.chars > 0 ? (parsed.response_format ? 'json' : 'npc') : 'other'
    }

    const url = new URL(req.url, UPSTREAM)
    const upstreamReq = https.request(
      { hostname: url.hostname, port: 443, path: url.pathname + url.search, method: req.method, headers: { ...req.headers, host: url.hostname } },
      (upstreamRes) => {
        const out = []
        upstreamRes.on('data', (c) => out.push(c))
        upstreamRes.on('end', () => {
          const body = Buffer.concat(out).toString('utf8')
          try {
            const json = JSON.parse(body)
            if (json.usage) {
              record.usage = {
                prompt: json.usage.prompt_tokens,
                completion: json.usage.completion_tokens,
                total: json.usage.total_tokens,
                // ⚠️ 必须记这两个字段，否则会得出错误结论。
                // DeepSeek 有**自动上下文缓存**：命中缓存的输入 token 单价远低于未命中。
                // 只看 prompt_tokens 会以为「system 提示词每轮都全价重发」，
                // 实际可能大部分在命中缓存 —— 那压缩提示词省下的**钱**就远小于省下的 token。
                // 反过来，若 cache_hit 一直是 0，说明前缀不稳定（比如历史插在 system 前面），
                // 那才是真正该修的问题。缺了这两个字段，两种情况的数字长得一模一样。
                cacheHit: json.usage.prompt_cache_hit_tokens ?? null,
                cacheMiss: json.usage.prompt_cache_miss_tokens ?? null,
                // 推理模型的「思考」token 也计入 completion，但**不显示给玩家**。
                // 输出占了总成本 62%，是最大的那一块；而这一块里
                // 「贵在思考」还是「贵在回复」决定了能不能动、怎么动：
                // 思考 token 不影响玩家看到的文字，回复 token 直接影响体验。
                // 拆不出来就只能说「输出贵」，拆出来了才谈得上做决策。
                reasoning: json.usage.completion_tokens_details?.reasoning_tokens ?? null,
                // 完整原始 usage 一并留档：上游加字段时不必再改代理、再重跑一遍测量。
                rawUsage: json.usage,
              }
              totals.calls += 1
              totals.promptTokens += json.usage.prompt_tokens || 0
              totals.completionTokens += json.usage.completion_tokens || 0
              totals.totalTokens += json.usage.total_tokens || 0
              totals.bytesIn += raw.length
              totals.cacheHit = (totals.cacheHit || 0) + (json.usage.prompt_cache_hit_tokens || 0)
              totals.cacheMiss = (totals.cacheMiss || 0) + (json.usage.prompt_cache_miss_tokens || 0)
              const kind = record.kind || 'other'
              byKind[kind] = byKind[kind] || { calls: 0, prompt: 0, completion: 0 }
              byKind[kind].calls += 1
              byKind[kind].prompt += json.usage.prompt_tokens || 0
              byKind[kind].completion += json.usage.completion_tokens || 0
              fs.writeFileSync(SUMMARY, JSON.stringify({ totals, byKind, updatedAt: record.at }, null, 2))
            }
            record.status = upstreamRes.statusCode
          } catch {
            record.status = upstreamRes.statusCode
          }
          fs.appendFileSync(JSONL, JSON.stringify(record) + '\n')
          console.log(`[meter] #${totals.calls} prompt=${record.usage?.prompt ?? '-'} completion=${record.usage?.completion ?? '-'} ` +
            `cacheHit=${record.usage?.cacheHit ?? '-'} ` +
            `sys=${record.system?.chars ?? '-'}ctx=${record.context?.chars ?? '-'}hist=${record.history?.chars ?? '-'}(×${record.historyMessages ?? '-'})`)
          res.writeHead(upstreamRes.statusCode || 502, { 'Content-Type': 'application/json; charset=utf-8' })
          res.end(body)
        })
      },
    )
    upstreamReq.on('error', (err) => {
      console.log('[meter] 上游错误:', err.message)
      res.writeHead(502, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ error: { message: 'meter proxy upstream error: ' + err.message } }))
    })
    upstreamReq.end(raw)
  })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[meter] 计量代理已启动：127.0.0.1:${PORT} → ${UPSTREAM}`)
  console.log(`[meter] 明细 ${JSONL}`)
  console.log(`[meter] 汇总 ${SUMMARY}`)
})
