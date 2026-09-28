/**
 * 谜题拖动排序交互测试。
 *
 * 需求明确要求「将事件拖动到正确时间」，而 HTML5 拖放是最容易「看起来写了但实际不生效」的交互，
 * 所以这里用真实浏览器把「拖动 → 顺序变化」跑一遍，而不是只检查代码里有没有 draggable。
 *
 * 用法：先启动前端（vite preview 或 dev），然后 node scripts/puzzle-drag-check.mjs
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs/promises'
import path from 'node:path'

const browserPath =
  process.env.BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const baseUrl = process.env.MINDTRACE_URL || 'http://127.0.0.1:5173'
const outputDir = path.resolve('..', 'artifacts')
await fs.mkdir(outputDir, { recursive: true })

const browser = await chromium.launch({ executablePath: browserPath, headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })

const consoleErrors = []
page.on('console', (message) => {
  if (message.type() === 'error') consoleErrors.push(message.text())
})
page.on('pageerror', (error) => consoleErrors.push(error.message))

const result = { baseUrl, checkedAt: new Date().toISOString(), consoleErrors, steps: [] }

await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' })
await page.getByPlaceholder('输入用户名').fill('demo_investigator')
await page.getByPlaceholder('输入密码').fill('demo123')
await page.getByRole('button', { name: '进入档案系统' }).click()
await page.waitForURL('**/home')

await page.goto(`${baseUrl}/case/1`, { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
await page.locator('nav.case-tabs button').filter({ hasText: '谜题' }).click()
await page.waitForTimeout(500)

// 第一个谜题（TIME_SORT）默认展开，向其中加入 3 个事件。
const sourceItems = page.locator('.puzzle-card').first().locator('.source-item')
for (let index = 0; index < 3; index += 1) {
  await sourceItems.nth(index).click()
}
await page.waitForTimeout(300)

const rowTexts = () =>
  page.locator('.puzzle-card').first().locator('.selected-row > span').allInnerTexts()

const before = await rowTexts()
result.steps.push({ step: '加入 3 个事件后的顺序', order: before })

if (before.length !== 3) {
  result.failure = `期望加入 3 行，实际 ${before.length} 行`
} else {
  const rows = page.locator('.puzzle-card').first().locator('.selected-row')

  // 把第 3 行拖到第 1 行：期望得到 [C, A, B]
  await rows.nth(2).dragTo(rows.nth(0))
  await page.waitForTimeout(400)

  const after = await rowTexts()
  result.steps.push({ step: '把第 3 行拖到第 1 行之后', order: after })

  const expected = [before[2], before[0], before[1]]
  result.dragReordered = JSON.stringify(after) === JSON.stringify(expected)
  result.expectedOrder = expected

  // 再拖回去：把第 1 行拖到第 3 行，期望恢复 [A, B, C]
  await rows.nth(0).dragTo(rows.nth(2))
  await page.waitForTimeout(400)

  const restored = await rowTexts()
  result.steps.push({ step: '再把第 1 行拖到第 3 行之后', order: restored })
  result.dragRestored = JSON.stringify(restored) === JSON.stringify(before)

  // 用 ↑↓ 按钮再确认一次后备操作仍然可用（可访问性要求）。
  await rows.nth(0).getByTitle('下移').click()
  await page.waitForTimeout(250)
  const afterArrow = await rowTexts()
  result.steps.push({ step: '点击第 1 行的「下移」之后', order: afterArrow })
  result.arrowMoved = afterArrow[0] === restored[1] && afterArrow[1] === restored[0]
}

await page.screenshot({ path: path.join(outputDir, '08-puzzle-drag.png'), fullPage: false })

await browser.close()
console.log(JSON.stringify(result, null, 2))

const ok = result.dragReordered && result.dragRestored && result.arrowMoved && consoleErrors.length === 0
process.exit(ok ? 0 : 1)
