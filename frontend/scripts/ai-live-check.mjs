/**
 * 浏览器级 AI 链路验收：确认 DeepSeek 的结果经 Java 后端处理后**真的到达 Vue 并渲染**，
 * 同时扫描浏览器侧（网络响应 / 页面源码 / localStorage）是否泄露 API Key。
 *
 * 与另外两个脚本的分工：
 *   verify-api.ps1          —— 直接打接口，验证后端契约
 *   e2e-full-loop.mjs       —— 完整一局游戏的持久化验收
 *   本脚本                   —— 只盯两件事：AI 回复有没有到前端；密钥有没有漏到前端
 *
 * 用法：先启动前端（5173）与**已配置真实 Key 的**后端（8080），然后
 *   cd frontend && node scripts/ai-live-check.mjs
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

// 只要出现 sk- 后跟 20 位以上字母数字，就认为疑似密钥泄露
const SECRET_PATTERN = /sk-[A-Za-z0-9]{20,}/
const knownPrefix = process.env.MINDTRACE_KEY_PREFIX || ''

const username = 'liveui' + Date.now()
const password = 'liveui123456'

const browser = await chromium.launch({ executablePath: browserPath, headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 960 } })
const page = await context.newPage()

// 记录所有响应体，供后续密钥扫描
const captured = []
page.on('response', async (response) => {
  const url = response.url()
  if (!url.includes('/api/')) return
  try {
    const text = await response.text()
    captured.push({ url, status: response.status(), body: text })
  } catch {
    /* 无 body 的响应忽略 */
  }
})
const consoleErrors = []
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text())
})
page.on('pageerror', (err) => consoleErrors.push(String(err)))

let chatResponse = null

try {
  // ---------- 1. 注册并进入 ----------
  await page.goto(`${baseUrl}/register`, { waitUntil: 'networkidle' })
  await page.getByPlaceholder('3-20 位字母数字').fill(username)
  await page.getByPlaceholder('排行榜显示名称').fill('前端实测')
  const pw = page.locator('input[type="password"]')
  await pw.nth(0).fill(password)
  await pw.nth(1).fill(password)
  await page.getByRole('button', { name: '创建档案并进入档案馆' }).click()
  await page.waitForURL('**/home', { timeout: 30000 })
  check('01 注册后进入档案馆', page.url().includes('/home'))

  // ---------- 2. 后端 AI 是否已配置 ----------
  const health = await page.evaluate(async () => {
    const r = await fetch('http://127.0.0.1:8080/api/health')
    return r.json()
  })
  const aiConfigured = health?.data?.deepSeekConfigured === true
  check('02 后端报告 DeepSeek 已配置', aiConfigured, `deepSeekConfigured=${aiConfigured}`)

  // ---------- 3. 打开案件并进入 NPC 对话 ----------
  await page.goto(`${baseUrl}/case/1`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const npcButtons = page.locator('.npc-selector button')
  const npcCount = await npcButtons.count()
  check('03 案件详情页有可对话的 NPC', npcCount > 0, `NPC 按钮 ${npcCount} 个`)

  const question = '请用一句话说明：当晚供水异常时你在哪里？'
  await npcButtons.first().click()
  await page.waitForTimeout(400)

  // 捕获这一次对话的响应体
  const waitResponse = page.waitForResponse(
    (r) => r.url().includes('/api/cases/1/chat') && r.request().method() === 'POST',
    { timeout: 120000 },
  )
  await page.locator('.terminal-input textarea').fill(question)
  await page.locator('.terminal-input button').click()
  const resp = await waitResponse
  chatResponse = await resp.json().catch(() => null)
  check('04 对话请求返回 200', resp.status() === 200, `HTTP ${resp.status()}`)

  const data = chatResponse?.data || {}
  check('05 响应标记 aiAvailable=true（走的是真实 AI，不是降级）', data.aiAvailable === true,
    `aiAvailable=${data.aiAvailable} aiNotice=${data.aiNotice}`)
  check('06 响应含非空 AI 回复', typeof data.reply === 'string' && data.reply.trim().length > 0,
    `回复 ${(data.reply || '').length} 字`)

  // ---------- 4. 前端是否真的渲染了这段 AI 文本 ----------
  await page.waitForTimeout(2000)
  const replyHead = (data.reply || '').slice(0, 18)
  const rendered = await page.evaluate(
    (head) => (document.querySelector('.terminal')?.textContent || '').includes(head),
    replyHead,
  )
  check('07 Vue 已把 AI 回复渲染到对话区', rendered,
    rendered ? `页面上找到「${replyHead}…」` : `页面上没找到「${replyHead}…」`)

  await page.screenshot({ path: path.join(outputDir, 'ai-live-chat.png'), fullPage: true })

  // ---------- 5. 密钥泄露扫描（浏览器侧） ----------
  const leakingResponses = captured
    .filter((c) => SECRET_PATTERN.test(c.body) || (knownPrefix && c.body.includes(knownPrefix)))
    .map((c) => c.url)
  check('08 所有 API 响应体中都没有密钥', leakingResponses.length === 0,
    leakingResponses.length ? leakingResponses.join(', ') : `扫描 ${captured.length} 条响应`)

  const pageHtml = await page.content()
  check('09 页面 DOM 中没有密钥', !SECRET_PATTERN.test(pageHtml))

  const storage = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))
  check('10 localStorage/sessionStorage 中没有密钥', !SECRET_PATTERN.test(storage))

  const scripts = await page.evaluate(() =>
    Array.from(document.querySelectorAll('script')).map((s) => s.textContent || '').join('\n'),
  )
  check('11 页面内联脚本中没有密钥', !SECRET_PATTERN.test(scripts))

  check('12 浏览器控制台无报错', consoleErrors.length === 0,
    consoleErrors.slice(0, 2).join(' | '))
} finally {
  await fs.writeFile(
    path.join(outputDir, 'ai-live-check.json'),
    JSON.stringify({
      username,
      checks,
      consoleErrors,
      capturedApiUrls: captured.map((c) => `${c.status} ${c.url}`),
      chatAiAvailable: chatResponse?.data?.aiAvailable ?? null,
      chatReplyLength: (chatResponse?.data?.reply || '').length,
    }, null, 2),
    'utf8',
  )
  await browser.close()
}

const failed = checks.filter((c) => !c.passed)
console.log(`\nSUMMARY total=${checks.length} passed=${checks.length - failed.length} failed=${failed.length}`)
process.exit(failed.length === 0 ? 0 : 1)
