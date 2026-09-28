/**
 * 浏览器级「多轮 NPC 对话」验收：确认玩家在真实页面上连续追问时，
 * 对话区能正确渲染整段会话，而不是只显示最后一轮。
 *
 * 为什么要单独一个脚本（和另外几个的分工）：
 *   verify-npc-voice.py   —— 128 轮真实 AI，但**走的是 HTTP**，不看页面
 *   ai-live-check.mjs     —— 看页面，但只发**一轮**
 *   e2e-full-loop.mjs     —— 覆盖「对话落库 + 刷新仍在」，也是**一轮**
 *   → 多轮会话在 UI 里怎么渲染（消息列表增长、输入框清空、整段会话是否还在），
 *     是唯一没被覆盖的一层。本脚本补这一层，用玩家视角跑完验收要求的那 10 轮。
 *
 * 顺带在这里复查两件「后端改了、前端要跟着对」的事：
 *   - 每轮回复渲染出来都不超过 200 字（后端提示词约束的落地效果）
 *   - 渲染出来的文本里没有半角标点（TextStyle 归一化有没有真的到达前端）
 *
 * 用法：先启动前端（5173）与**已配置真实 Key 的**后端（8080），然后
 *   cd frontend && node scripts/npc-chat-ui-check.mjs
 *
 * 退出码：全部通过为 0，出现 FAIL 为 1。
 */
import fs from 'node:fs/promises'
import path from 'node:path'
import { chromium } from 'playwright-core'

const browserPath =
  process.env.BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const baseUrl = process.env.MINDTRACE_URL || 'http://127.0.0.1:5173'
const outputDir = path.resolve('..', 'artifacts')
await fs.mkdir(outputDir, { recursive: true })

const checks = []
const check = (name, ok, detail = '') => {
  checks.push({ name, passed: Boolean(ok), detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? `  (${detail})` : ''}`)
  return Boolean(ok)
}

// 玩家视角：验收要求里点名的 10 组对话（含连续追问与前后文关联）
const QUESTIONS = [
  ['时间线', '关于时间线，你能说点什么？'],
  ['询问人物', '当时酒店里有哪些人？'],
  ['职责', '你当时负责什么工作？'],
  ['电梯异常', '你还记得电梯当时有什么异常吗？'],
  ['网络线索', '关于网络，你知道些什么？'],
  ['追问时间码', '你刚才说时间码有问题，具体是什么？'],
  ['推测电梯里的人', '那你觉得电梯里的人在干什么？'],
  ['认识其他人吗', '你认识当时酒店里的其他人吗？'],
  ['前后文关联', '等等，你前面提到登记本，那上面还有别的记录吗？'],
  ['连续追问', '电梯门为什么一直开着？'],
]

const REPLY_SOFT_LIMIT = 200
// 中文里混入半角标点 —— 一眼就像机器生成的文本（后端 TextStyle 负责归一化，这里验证它到没到前端）
const HALF_PUNCT = /[\u4e00-\u9fa5][,:?!;]|[,:?!;][\u4e00-\u9fa5]/
const strip = (text) => (text || '').replace(/\s/g, '')

const username = 'npcui' + Date.now()
const password = 'npcui123456'

const browser = await chromium.launch({ executablePath: browserPath, headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 960 } })
const page = await context.newPage()

const consoleErrors = []
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text())
})
page.on('pageerror', (err) => consoleErrors.push(String(err)))

const MESSAGE_SELECTOR = '.terminal .message, .terminal .chat-message, .terminal .bubble'
const countMessages = () => page.evaluate(
  (sel) => document.querySelectorAll(sel).length, MESSAGE_SELECTOR)

const turns = []
let fatal = ''

try {
  // ---------- 1. 注册并进入案件 ----------
  await page.goto(`${baseUrl}/register`, { waitUntil: 'networkidle' })
  await page.getByPlaceholder('3-20 位字母数字').fill(username)
  await page.getByPlaceholder('排行榜显示名称').fill('多轮对话实测')
  const pw = page.locator('input[type="password"]')
  await pw.nth(0).fill(password)
  await pw.nth(1).fill(password)
  await page.getByRole('button', { name: '创建档案并进入档案馆' }).click()
  await page.waitForURL('**/home', { timeout: 30000 })
  check('01 注册后进入档案馆', page.url().includes('/home'))

  await page.goto(`${baseUrl}/case/1`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const npcButtons = page.locator('.npc-selector button')
  check('02 案件详情页有可对话的 NPC', (await npcButtons.count()) > 0,
    `NPC 按钮 ${await npcButtons.count()} 个`)
  await npcButtons.first().click()
  await page.waitForTimeout(400)

  // ---------- 2. 逐轮发问（同一会话，上下文连续） ----------
  console.log('\n--- 逐轮发送（同一会话） ---')
  for (const [label, question] of QUESTIONS) {
    const before = await countMessages()
    const waiting = page.waitForResponse(
      (r) => r.url().includes('/api/cases/1/chat') && r.request().method() === 'POST',
      { timeout: 120000 },
    )
    await page.locator('.terminal-input textarea').fill(question)
    await page.locator('.terminal-input button').click()
    const response = await waiting
    const payload = await response.json().catch(() => null)
    const data = payload?.data || {}
    const reply = data.reply || ''

    // 等这一段回复真的出现在 DOM 里（而不是固定 sleep 猜时间）
    const head = strip(reply).slice(0, 20)
    let rendered = false
    try {
      await page.waitForFunction(
        ({ sel, needle }) => {
          const text = Array.from(document.querySelectorAll(sel))
            .map((n) => n.textContent || '').join('').replace(/\s/g, '')
          return needle.length > 0 && text.includes(needle)
        },
        { sel: MESSAGE_SELECTOR, needle: head },
        { timeout: 30000 },
      )
      rendered = true
    } catch {
      rendered = false
    }
    await page.waitForTimeout(300)
    const after = await countMessages()
    const inputCleared = (await page.locator('.terminal-input textarea').inputValue()) === ''

    turns.push({
      label, question, reply, rendered, inputCleared,
      chars: strip(reply).length, aiAvailable: data.aiAvailable,
      countBefore: before, countAfter: after, delta: after - before,
      halfPunct: HALF_PUNCT.test(reply),
    })
    console.log(`  [${label}] ${strip(reply).length} 字 · 消息 ${before}→${after} · 渲染=${rendered}`)
  }

  const total = turns.length
  check('03 每一轮的回复都真的渲染到了对话区', turns.every((t) => t.rendered),
    turns.filter((t) => !t.rendered).map((t) => t.label).join('、') || `${total}/${total} 轮都渲染出来了`)

  // 每轮应新增 2 条（玩家 1 条 + NPC 1 条）；只增 1 条说明旧消息被顶掉了 —— 那就是「只显示最后一轮」
  const badDelta = turns.filter((t) => t.delta !== 2)
  check('04 对话区是累加的：每轮新增 2 条消息（玩家 + NPC）', badDelta.length === 0,
    badDelta.length
      ? badDelta.map((t) => `${t.label}: 新增 ${t.delta} 条`).join('；')
      : `最终消息数 ${turns[turns.length - 1].countAfter}`)

  const notCleared = turns.filter((t) => !t.inputCleared)
  check('05 每次发送后输入框被清空', notCleared.length === 0,
    notCleared.map((t) => t.label).join('、') || `${total} 轮都清空了`)

  // ---------- 3. 后端两处改动的落地效果 ----------
  const over = turns.filter((t) => t.chars > REPLY_SOFT_LIMIT)
  check('06 渲染出来的回复没有一轮超过 200 字', over.length === 0,
    over.map((t) => `${t.label}(${t.chars}字)`).join('、')
    || `最长 ${Math.max(...turns.map((t) => t.chars))} 字`)

  const halfWidth = turns.filter((t) => t.halfPunct)
  check('07 渲染出来的回复里没有半角标点（TextStyle 已到达前端）', halfWidth.length === 0,
    halfWidth.map((t) => t.label).join('、') || `${total} 轮都是中文全角`)

  const degraded = turns.filter((t) => t.aiAvailable !== true)
  check('08 全部走真实 AI（非降级）', degraded.length === 0,
    degraded.length
      ? `${degraded.length}/${total} 轮降级：${degraded.map((t) => t.label).join('、')}`
      : `${total}/${total} 轮 aiAvailable=true`)

  // ---------- 4. 刷新后整段会话还在（多轮持久化，不只是最后一轮） ----------
  await page.screenshot({ path: path.join(outputDir, 'npc-chat-ui.png'), fullPage: true })
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  const terminalText = strip(await page.evaluate(
    () => document.querySelector('.terminal')?.textContent || ''))
  const missing = QUESTIONS.filter(([, q]) => !terminalText.includes(strip(q)))
  check('09 刷新后 10 轮问题全部还在（多轮会话真的落库了）', missing.length === 0,
    missing.map(([label]) => label).join('、') || `${QUESTIONS.length} 轮全部仍在`)

  const missingReplies = turns.filter((t) => !terminalText.includes(strip(t.reply).slice(0, 20)))
  check('10 刷新后 NPC 的回复也全部还在', missingReplies.length === 0,
    missingReplies.map((t) => t.label).join('、') || `${turns.length} 轮回复全部仍在`)

  // ---------- 5. 控制台 ----------
  check('11 全程无浏览器控制台错误', consoleErrors.length === 0,
    consoleErrors.length ? consoleErrors.slice(0, 3).join(' | ') : '0 条')
} catch (error) {
  fatal = String(error && error.stack ? error.stack : error)
  console.log(`\n运行中断：${fatal}`)
} finally {
  await browser.close()
}

const passed = checks.filter((c) => c.passed).length
const failed = checks.length - passed
const report = {
  ranAt: new Date().toISOString(),
  account: username,
  turns,
  checks,
  consoleErrors,
  fatal,
  summary: { total: checks.length, passed, failed },
}
await fs.writeFile(path.join(outputDir, 'npc-chat-ui-check.json'),
  JSON.stringify(report, null, 2), 'utf-8')

console.log('\n' + '='.repeat(60))
console.log(`SUMMARY total=${checks.length} passed=${passed} failed=${failed}`)
if (turns.length) {
  const lens = turns.map((t) => t.chars).sort((a, b) => a - b)
  console.log(`对话 ${turns.length} 轮 · 最长 ${lens[lens.length - 1]} 字 · 中位数 ${lens[Math.floor(lens.length / 2)]} 字`)
}
console.log(`报告已写入 ${path.join(outputDir, 'npc-chat-ui-check.json')}`)
console.log(`截图已写入 ${path.join(outputDir, 'npc-chat-ui.png')}`)

process.exit(failed > 0 || fatal ? 1 : 0)
