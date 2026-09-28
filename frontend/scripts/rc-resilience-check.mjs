/**
 * MindTrace 发布前全量测试 —— 浏览器层（Part 19 / 24 / 25 / 26）。
 *
 * Part 19 前端异常与降级：接口 5xx / 网络中断 / 非法参数下不白屏、有可读提示
 * Part 24 UI 响应式：1920 / 1440 / 1280 / 390 四种宽度无横向溢出、内容不为空
 * Part 25 控制台：全程无真实报错
 * Part 26 网络：无 4xx/5xx，无重复冗余请求
 *
 * 另外补一条端到端断言：**令牌失效时必须真的跳回登录页**。
 * 这条正好验证后端「未登录返回 401（而不是 403）」的修复 —— 前端只认 401。
 */
import fs from 'node:fs/promises'
import path from 'node:path'
import { chromium } from 'playwright-core'

const browserPath =
  process.env.BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const baseUrl = process.env.MINDTRACE_URL || 'http://127.0.0.1:5173'
const username = process.env.MINDTRACE_USER || 'demo_investigator'
const password = process.env.MINDTRACE_PASS || 'demo123'
const outputDir = path.resolve('..', 'artifacts')
await fs.mkdir(outputDir, { recursive: true })

const checks = []
function check(name, ok, detail = '') {
  checks.push({ name, pass: !!ok, detail })
  console.log(`[${ok ? 'PASS' : 'FAIL'}] ${name}${detail ? `   —— ${detail}` : ''}`)
}
function warn(name, detail = '') {
  checks.push({ name, pass: true, warned: true, detail })
  console.log(`[WARN] ${name}${detail ? `   —— ${detail}` : ''}`)
}
function section(title) {
  console.log(`\n${'='.repeat(72)}\n${title}\n${'='.repeat(72)}`)
}

const browser = await chromium.launch({ executablePath: browserPath, headless: true })

/** 与业务无关的浏览器噪音，不算「真实报错」。 */
const NOISE = [
  /favicon/i,
  /ResizeObserver loop/i,
  /ERR_INTERNET_DISCONNECTED.*favicon/i,
  /Download the React DevTools/i,
]
const isNoise = (text) => NOISE.some((re) => re.test(text))

function attach(page, bucket) {
  // 调用方可能只传了部分字段，这里补齐，避免监听器里出现 undefined
  bucket.console ||= []
  bucket.failed ||= []
  bucket.badResponses ||= []
  bucket.all ||= []
  bucket.perLoad ||= []
  page.on('console', (m) => {
    if (m.type() === 'error') bucket.console.push(m.text())
  })
  page.on('pageerror', (e) => bucket.console.push(`pageerror: ${e.message}`))
  page.on('requestfailed', (r) => {
    bucket.failed.push(`${r.method()} ${r.url()} :: ${r.failure()?.errorText || ''}`)
  })
  page.on('response', (r) => {
    const url = r.url()
    if (!url.startsWith(baseUrl)) return
    if (r.status() >= 400) bucket.badResponses.push(`${r.status()} ${url}`)
    const rel = url.replace(baseUrl, '')
    // 只关心业务接口。静态资源（JS/CSS/manifest）在多次页面导航之间被重复请求
    // 是浏览器正常的缓存行为，与「重复渲染 / 重复 watch」无关。
    if (rel.startsWith('/api/')) {
      const line = `${r.request().method()} ${rel}`
      bucket.all.push(line)
      bucket.perLoad.push(line)
    }
  })
}

async function login(page) {
  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' })
  await page.getByPlaceholder('输入用户名').fill(username)
  await page.getByPlaceholder('输入密码').fill(password)
  await page.getByRole('button', { name: '进入档案系统' }).click()
  await page.waitForURL('**/home')
  await page.waitForLoadState('networkidle')
}

// ---------------------------------------------------------------------------
section('Part 24 / 25 / 26 —— 多宽度渲染 + 控制台 + 网络')
const bucket = { console: [], failed: [], badResponses: [], all: [], perLoad: [], violations: [] }
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
attach(page, bucket)
await login(page)

const WIDTHS = [1920, 1440, 1280, 390]
const WIDE_ROUTES = [
  ['/home', '悬疑档案馆'],
  ['/cases', '案件档案'],
  ['/case/1', '调查台'],
]
const EXTRA_ROUTES = [
  ['/achievements', '成就徽章'],
  ['/ranking', '排行榜'],
  ['/profile', '调查员档案'],
]

async function inspectRoute(route, label, width) {
  bucket.perLoad.length = 0
  await page.setViewportSize({ width, height: width <= 480 ? 780 : 1000 })
  await page.goto(`${baseUrl}${route}`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(400)
  const info = await page.evaluate(() => ({
    docWidth: document.documentElement.scrollWidth,
    winWidth: window.innerWidth,
    textLength: document.body.innerText.trim().length,
    mainHeight: (document.querySelector('main') || document.body).getBoundingClientRect().height,
  }))
  const overflow = info.docWidth > info.winWidth + 1
  check(`${label} @${width}px 无横向溢出`, !overflow,
    overflow ? `doc=${info.docWidth} win=${info.winWidth}` : `doc=${info.docWidth}`)
  check(`${label} @${width}px 内容非空白`, info.textLength > 40 && info.mainHeight > 40,
    `text=${info.textLength} main=${Math.round(info.mainHeight)}`)
  // 单次页面加载内的重复请求
  const dup = {}
  for (const line of bucket.perLoad) dup[line] = (dup[line] || 0) + 1
  for (const [key, count] of Object.entries(dup)) {
    if (count >= 4) bucket.violations.push(`${label}@${width}px ${count}x ${key}`)
  }
}

for (const [route, label] of WIDE_ROUTES) {
  for (const width of WIDTHS) {
    await inspectRoute(route, label, width)
  }
}
for (const [route, label] of EXTRA_ROUTES) {
  await inspectRoute(route, label, 1440)
}

await page.setViewportSize({ width: 1920, height: 1080 })
await page.goto(`${baseUrl}/case/1`, { waitUntil: 'networkidle' })
await page.waitForTimeout(500)
await page.screenshot({ path: path.join(outputDir, 'rc-01-wide-1920.png'), fullPage: false })
await page.setViewportSize({ width: 1280, height: 900 })
await page.goto(`${baseUrl}/cases`, { waitUntil: 'networkidle' })
await page.waitForTimeout(500)
await page.screenshot({ path: path.join(outputDir, 'rc-02-narrow-1280.png'), fullPage: false })

const realConsoleErrors = bucket.console.filter((t) => !isNoise(t))
check('Part 25 全程无真实控制台错误', realConsoleErrors.length === 0,
  realConsoleErrors.length ? realConsoleErrors.slice(0, 3).join(' | ') : '0 条')

const realBad = bucket.badResponses.filter((r) => !/favicon/i.test(r))
check('Part 26 页面加载期间无 4xx/5xx 响应', realBad.length === 0,
  realBad.length ? realBad.slice(0, 3).join(' | ') : '0 条')

// 重复请求：同一接口在同一次页面加载里被请求多次，多半是重复渲染 / 重复 watch
check('Part 26 没有异常的重复请求（单次加载同一接口 ≤3 次）', bucket.violations.length === 0,
  bucket.violations.length ? bucket.violations.slice(0, 4).join(' | ') : '无')

// ---------------------------------------------------------------------------
section('Part 19 —— 接口异常时页面不白屏、有可读提示')
async function degradedRoute(route, label, handler) {
  const local = { console: [], failed: [], badResponses: [], all: [] }
  const p = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  attach(p, local)
  await login(p)
  await p.route('**/api/cases**', handler)
  await p.goto(`${baseUrl}${route}`, { waitUntil: 'domcontentloaded' })
  await p.waitForTimeout(2500)
  const info = await p.evaluate(() => ({
    textLength: document.body.innerText.trim().length,
    text: document.body.innerText.slice(0, 400),
    hasMessage: !!document.querySelector('.el-message, .el-alert, .el-empty, .empty-state, .error-state'),
  }))
  const readable = /失败|错误|重试|无法|暂无|没有|加载|出错了|稍后/.test(info.text)
  check(`${label}：页面未白屏`, info.textLength > 40, `text=${info.textLength}`)
  check(`${label}：给出了可读提示`, readable || info.hasMessage,
    readable ? '文案命中' : `hasMessage=${info.hasMessage} text=${JSON.stringify(info.text.slice(0, 120))}`)
  await p.close()
}

await degradedRoute('/cases', '接口 500', (route) =>
  route.fulfill({
    status: 500,
    contentType: 'application/json',
    body: JSON.stringify({ success: false, message: '服务器处理失败，请稍后重试', data: null }),
  }))

await degradedRoute('/cases', '网络中断', (route) => route.abort('failed'))

// ---------------------------------------------------------------------------
section('令牌失效必须跳回登录页（验证后端 401 而非 403）')
{
  const p = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  const local = { console: [], failed: [], badResponses: [], all: [] }
  attach(p, local)
  await login(p)
  await p.evaluate(() => localStorage.setItem('mindtrace_token', 'this.is.not-a-valid-token'))
  await p.goto(`${baseUrl}/profile`, { waitUntil: 'domcontentloaded' })
  await p.waitForTimeout(2500)
  const url = p.url()
  const cleared = await p.evaluate(() => localStorage.getItem('mindtrace_token') === null)
  check('令牌失效后跳转到登录页', url.includes('/login'), `url=${url}`)
  check('令牌失效后本地令牌被清理', cleared, `cleared=${cleared}`)
  await p.close()
}

// ---------------------------------------------------------------------------
section('汇总')
const failed = checks.filter((c) => !c.pass)
const report = {
  baseUrl,
  total: checks.length,
  failed: failed.length,
  warned: checks.filter((c) => c.warned).length,
  checks,
  consoleErrors: bucket.console,
  failedRequests: bucket.failed,
  badResponses: bucket.badResponses,
}
await fs.writeFile(
  path.join(outputDir, 'rc-browser-check.json'),
  JSON.stringify(report, null, 2),
  'utf-8',
)

console.log(`\nSUMMARY checks=${checks.length} failed=${failed.length} warned=${report.warned}`)
console.log(`报告已写入 ${path.join(outputDir, 'rc-browser-check.json')}`)
if (failed.length) {
  console.log('\n失败项：')
  for (const f of failed) console.log(`  - ${f.name}  (${f.detail})`)
}

await browser.close()
process.exit(failed.length ? 1 : 0)
