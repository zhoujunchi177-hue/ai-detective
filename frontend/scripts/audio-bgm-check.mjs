/**
 * 浏览器级「背景音乐是否真的能播」验收。
 *
 * 为什么需要单独一层：
 *   前面的检查（manifest.json 内容、mp3 能否 200 拿到）都只是**零件**，
 *   真正会让玩家说「放进去还是没声音」的是**接线**：
 *     manifest 的 true  →  store 建 <audio>  →  文件能解码  →  首次交互后 play() 成功
 *   任何一环断掉，零件全对也没声音。本脚本按这条链逐环验证。
 *
 * 两个踩过的坑（别改回去）：
 *   1. 媒体请求返回 **206 Partial Content** 是正常的（浏览器用 Range 取音频），
 *      断言「必须 200」会假失败。
 *   2. store 用的是 `new Audio(...)`，**该元素不会挂到 DOM 上**，
 *      所以 `document.querySelector('audio')` 永远拿不到 —— 必须拦截构造器。
 *
 * 用法：先启动前端（5173）与后端（8080，用于登录），然后
 *   cd frontend && node scripts/audio-bgm-check.mjs
 *
 * 退出码：全部通过为 0，出现 FAIL 为 1。
 */
import { chromium } from 'playwright-core'

const browserPath =
  process.env.BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const baseUrl = process.env.MINDTRACE_URL || 'http://127.0.0.1:5173'
const apiUrl = process.env.MINDTRACE_API || 'http://127.0.0.1:8080'

const TRACKS = ['lobby', 'investigation', 'tension', 'result']

const checks = []
const check = (name, ok, detail = '') => {
  checks.push({ name, passed: Boolean(ok), detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? `  (${detail})` : ''}`)
  return Boolean(ok)
}

const login = await fetch(`${apiUrl}/api/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'demo_investigator', password: 'demo123' }),
}).then((r) => r.json())
if (!login?.data?.token) {
  console.log('FAIL 无法登录后端，无法继续（后端起了吗？）')
  process.exit(1)
}

const browser = await chromium.launch({ executablePath: browserPath, headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 960 } })

await context.addInitScript((token) => {
  localStorage.setItem('mindtrace_token', token)

  // store 用 new Audio() 创建元素且不插入 DOM，只能拦构造器才观察得到
  window.__audioEls = []
  window.__playErrors = []
  const OrigAudio = window.Audio
  const Wrapped = function (...args) {
    const el = new OrigAudio(...args)
    window.__audioEls.push(el)
    return el
  }
  Wrapped.prototype = OrigAudio.prototype
  window.Audio = Wrapped

  const origPlay = HTMLMediaElement.prototype.play
  HTMLMediaElement.prototype.play = function () {
    const result = origPlay.call(this)
    if (result && typeof result.catch === 'function') {
      result.catch((err) => window.__playErrors.push(String((err && err.name) || err)))
    }
    return result
  }
}, login.data.token)

const page = await context.newPage()

const audioRequests = []
const consoleErrors = []
page.on('response', (res) => {
  const url = res.url()
  if (url.includes('/audio/')) {
    audioRequests.push({ url: url.split('/audio/')[1], status: res.status() })
  }
})
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text())
})
page.on('pageerror', (err) => consoleErrors.push(String(err)))

await page.goto(baseUrl, { waitUntil: 'networkidle' })

const manifest = await page.evaluate(async () => {
  const res = await fetch('/audio/manifest.json', { cache: 'no-store' })
  return res.json()
})
const enabled = TRACKS.filter((t) => manifest?.tracks?.[t] === true)
check('manifest 里四个槽位都开启', enabled.length === 4, `已开启 ${enabled.join(',') || '无'}`)

const mp3Requests = audioRequests.filter((r) => r.url.endsWith('.mp3'))
check('前端真的发起了 mp3 请求', mp3Requests.length > 0,
  mp3Requests.map((r) => `${r.url}=${r.status}`).join(' ') || '一次都没有')
check('mp3 请求成功（200 或 206 都算，媒体常用 Range）',
  mp3Requests.length > 0 && mp3Requests.every((r) => r.status === 200 || r.status === 206),
  mp3Requests.map((r) => r.status).join(','))

const readState = () =>
  page.evaluate(() => {
    const els = window.__audioEls || []
    return els.map((el) => ({
      src: el.getAttribute('src'),
      readyState: el.readyState,
      paused: el.paused,
      currentTime: el.currentTime,
      duration: Number.isFinite(el.duration) ? el.duration : 0,
      volume: el.volume,
      error: el.error ? el.error.code : null,
    }))
  })

const created = await readState()
check('store 真的创建了 Audio 实例', created.length > 0,
  created.map((c) => c.src).join(',') || '一个都没有')
check('Audio 实例指向 /audio/ 下的文件',
  created.length > 0 && created.every((c) => (c.src || '').startsWith('/audio/')),
  created.map((c) => c.src).join(','))

// 浏览器自动播放策略：手势之前 play() 必然被拒（NotAllowedError），这是**设计预期**，
// 不是缺陷 —— README 明确写了「这不是错误状态，而是『等一下就播』」。
// 所以只在**手势之后**统计拒绝，才是有意义的判据（否则这条断言与设计自相矛盾）。
const preGestureErrors = await page.evaluate(() => (window.__playErrors || []).slice())
console.log(`     （手势前预期被拒 ${preGestureErrors.length} 次：${preGestureErrors.join(',') || '无'}）`)

await page.mouse.click(700, 400)
await page.evaluate(() => { window.__playErrors = [] })
await page.waitForTimeout(3000)

const after = await readState()
const main = after[after.length - 1]
const playErrors = await page.evaluate(() => window.__playErrors || [])

check('音频没有解码/加载错误', Boolean(main) && main.error === null, main ? `error=${main.error}` : '无实例')
check('已解码到可播状态（readyState>=2）', Boolean(main) && main.readyState >= 2,
  main ? `readyState=${main.readyState}` : '')
check('时长已解析（duration>0）', Boolean(main) && main.duration > 0,
  main ? `duration=${Math.round(main.duration)}s` : '')
check('首次交互后真的在播放（paused=false 且 currentTime 前进）',
  Boolean(main) && !main.paused && main.currentTime > 0,
  main ? `paused=${main.paused} currentTime=${main.currentTime.toFixed(2)}s` : '')
check('音量不是 0', Boolean(main) && main.volume > 0, main ? `volume=${main.volume}` : '')
check('手势之后 play() 没有被拒绝', playErrors.length === 0, playErrors.join(',') || '无拒绝')

const relevantErrors = consoleErrors.filter((t) => /audio|NotAllowed|decode/i.test(t))
check('没有与音频相关的控制台报错', relevantErrors.length === 0,
  relevantErrors.slice(0, 3).join(' | ') || '干净')

await browser.close()

const failed = checks.filter((c) => !c.passed).length
console.log('\n' + '='.repeat(60))
console.log(`SUMMARY total=${checks.length} passed=${checks.length - failed} failed=${failed}`)
console.log('音频请求：', audioRequests.map((r) => `${r.url}(${r.status})`).join(', ') || '无')
process.exit(failed === 0 ? 0 : 1)
