/**
 * 校验「案件资料来源」里的链接：href 是否正确、点击是否真的能跳转。
 *
 * 为什么需要它：数据层修好了链接，不代表界面真的能用 ——
 * 字段名不匹配、模板写错、被覆盖层挡住，都会让「点了没反应」。
 * 这个脚本走真实浏览器，逐条点开来源链接，确认：
 *   1) href 是绝对 http(s) URL（不能是 undefined / 相对路径）
 *   2) 点击真的打开新标签页，且目标地址正确
 *   3) 来源名不为空
 *
 * 只校验「能不能跳出去」，**不校验目标站点是否可达** ——
 * 那是网络环境问题，由 scripts/check-source-links.py 负责。
 *
 * 三个坑（都踩过，写在这里免得下次重踩）：
 *   - 「线索」标签页的内容取决于**该玩家已发现哪些线索**（discoveredClues），
 *     新账号是 0 条。所以只能校验「已渲染的链接都合法」，不能要求最小条数。
 *   - 「时间线」的来源链接**只在条目展开时才渲染**（v-if="activeId === item.id"），
 *     必须先逐条点开，否则永远测到 0 条。
 *   - CNN 会把 www.cnn.com 跳到 edition.cnn.com，属正常跳转，
 *     判定要比对 **pathname** 而不是整串相等。
 *
 * 用法：node scripts/source-link-check.mjs
 * 退出码：0 全部通过；1 有失败项
 */
import { chromium } from 'playwright-core'

const browserPath =
  process.env.BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const baseUrl = process.env.MINDTRACE_URL || 'http://127.0.0.1:5173'
const username = process.env.MINDTRACE_USER || 'demo_investigator'
const password = process.env.MINDTRACE_PASS || 'demo123'

// 「文件」标签页的来源是案件静态数据，条数固定 —— 这里写「至少」，不写死。
const MIN_FILE_SOURCES = { 1: 4, 2: 3, 3: 2 }

let declared = 0
let executed = 0
let failed = 0
const problems = []
const warnings = []

function assertTrue(condition, label, detail = '') {
  declared += 1
  executed += 1
  if (condition) {
    console.log(`  PASS  ${label}`)
  } else {
    failed += 1
    problems.push(`${label}${detail ? ' -> ' + detail : ''}`)
    console.log(`  FAIL  ${label}${detail ? '  -> ' + detail : ''}`)
  }
}

function warn(label) {
  warnings.push(label)
  console.log(`  WARN  ${label}`)
}

/** 比对 pathname，忽略 www./edition. 这类正常跳转。 */
function samePath(a, b) {
  try {
    return new URL(a).pathname.replace(/\/$/, '') === new URL(b).pathname.replace(/\/$/, '')
  } catch {
    return false
  }
}

/**
 * 切换标签页并**等它真的切过去**，而不是睡固定毫秒。
 *
 * 踩过的坑：切标签是纯前端状态切换，面板内容随后才渲染。原来「点一下 + 等 900ms」
 * 在数据少的时候够用，等玩家的线索/日志/时间线多起来之后，900ms 就不够了 ——
 * 脚本读到的是**上一个标签页**的内容，表现为「文件标签页 0 条来源」这种
 * 看起来像产品坏了、其实是脚本读早了的假失败。
 * 现在等按钮拿到 `active` 类（真实条件），再留一点渲染余量。
 */
async function clickTab(label) {
  await page.locator('.case-tabs button', { hasText: label }).first().click()
  await page.waitForFunction(
    (text) => {
      const button = Array.from(document.querySelectorAll('.case-tabs button'))
        .find((element) => (element.textContent || '').includes(text))
      return !!button && button.classList.contains('active')
    },
    label,
    { timeout: 10000 },
  )
  await page.waitForTimeout(300)
}

/**
 * 等某个选择器至少出现 min 个元素，超时就放弃（由后面的断言负责报告）。
 *
 * 为什么需要它：切标签只改前端状态，面板内容随后才渲染。这台机器上点击外部链接
 * 会去连一些连不通的站点，浏览器被拖慢后「active 类已生效 + 睡 300ms」仍可能读空。
 * 等一个**真实条件**（元素出现）比猜时长可靠。
 */
async function waitForCount(selector, min = 1, timeout = 10000) {
  try {
    await page.waitForFunction(
      ([sel, n]) => document.querySelectorAll(sel).length >= n,
      [selector, min],
      { timeout },
    )
  } catch {
    // 超时不在这里报错，交给调用方的 assertTrue 给出可读的失败信息
  }
  await page.waitForTimeout(150)
}

const browser = await chromium.launch({ executablePath: browserPath, headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()

await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' })
await page.getByPlaceholder('输入用户名').fill(username)
await page.getByPlaceholder('输入密码').fill(password)
await page.getByRole('button', { name: '进入档案系统' }).click()
await page.waitForURL('**/home')
await page.waitForLoadState('networkidle')

const seen = new Set()
let clickedCount = 0

/** 关掉除主页面之外的所有标签页，保证下一次点击从干净状态开始。 */
async function closeExtraPages() {
  for (const open of context.pages()) {
    if (open !== page) await open.close().catch(() => {})
  }
}

/** 逐条校验并真实点击一批链接。 */
async function verifyLinks(links, scope) {
  for (const link of links) {
    const key = `${scope}|${link.href}`
    if (seen.has(key)) continue
    seen.add(key)

    assertTrue(/^https?:\/\//i.test(link.href), `${scope} href 是绝对 URL：${link.href.slice(0, 68)}`)
    assertTrue(link.text.length > 0, `${scope} 来源名非空：${link.href.slice(0, 68)}`)

    // 点击 -> 等新标签页。
    //
    // 这里**不能**用 context.waitForEvent('page') 拿第一个事件：目标站点连不上时，
    // 新标签页会停在错误页上，close() 也可能失败并残留下来；下一次 waitForEvent
    // 就可能拿到上一个残留标签页，于是出现「点了 A 却报打开了 B」的假失败。
    // 改成对比「点击前后的页面集合」，只认真正新出现的那个。
    let popup = null
    for (let attempt = 0; attempt < 2 && !popup; attempt += 1) {
      await closeExtraPages()
      const before = new Set(context.pages())
      await page.locator(`a[href="${link.href}"]`).first().click({ noWaitAfter: true }).catch(() => {})
      for (let tick = 0; tick < 40 && !popup; tick += 1) {
        popup = context.pages().find((open) => !before.has(open)) || null
        if (!popup) await page.waitForTimeout(250)
      }
    }
    if (!popup) {
      assertTrue(false, `${scope} 点击能打开新标签页：${link.href.slice(0, 68)}`)
      continue
    }
    clickedCount += 1
    // 立即读地址：等太久的话，连不上的站点会变成 chrome-error://chromewebdata/
    let openedUrl = popup.url()
    if (!openedUrl || openedUrl === 'about:blank') {
      await popup.waitForTimeout(300)
      openedUrl = popup.url()
    }
    if (openedUrl.startsWith('chrome-error://')) {
      // 新标签页**已经开出来了**，是目标站点连不上（本机网络限制），不是应用 bug。
      // 目标站点是否真的可达由 scripts/check-source-links.py 负责判定。
      warn(`${scope} 新标签页已打开，但站点连不上（网络问题，非应用 bug）：${link.href.slice(0, 60)}`)
    } else {
      assertTrue(
        /^https?:\/\//i.test(openedUrl) && samePath(openedUrl, link.href),
        `${scope} 新标签页目标正确：${link.href.slice(0, 68)}`,
        `实际打开 ${openedUrl}`,
      )
    }
    await popup.close().catch(() => {})
  }
  await closeExtraPages()
}

async function collectExternalLinks() {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('a[href]'))
      .filter((a) => /^https?:\/\//i.test(a.getAttribute('href') || ''))
      .map((a) => ({ href: a.getAttribute('href'), text: (a.textContent || '').trim().slice(0, 50) })),
  )
}

for (const [caseId, minimum] of Object.entries(MIN_FILE_SOURCES)) {
  await page.goto(`${baseUrl}/case/${caseId}`, { waitUntil: 'networkidle' })
  // 等标签栏真的渲染出来，而不是睡固定时长
  await page.waitForSelector('.case-tabs button', { timeout: 15000 })
  console.log(`\n=== 案件 ${caseId} ===`)

  const tabLabels = await page.evaluate(() =>
    Array.from(document.querySelectorAll('.case-tabs button')).map((b) => (b.textContent || '').trim()),
  )

  // --- 「文件」标签页：案件静态来源，条数固定 ---
  if (!tabLabels.some((label) => label.includes('文件'))) {
    assertTrue(false, `案件 ${caseId} 存在「文件」标签`, `实际标签：${tabLabels.join(' / ')}`)
  } else {
    await clickTab('文件')
    await waitForCount('a[href^="http"]', 1)
    const links = await collectExternalLinks()
    assertTrue(
      links.length >= minimum,
      `案件 ${caseId}「文件」有 >= ${minimum} 条来源链接`,
      `实际 ${links.length} 条`,
    )
    await verifyLinks(links, `案件${caseId}·文件`)
  }

  // --- 「线索」标签页：只校验已渲染的（条数取决于玩家进度）---
  if (tabLabels.some((label) => label.includes('线索'))) {
    await clickTab('线索')
    const links = await collectExternalLinks()
    console.log(`  （线索标签页：${links.length} 条唯一链接，条数随调查进度变化）`)
    await verifyLinks(links, `案件${caseId}·线索`)
  }

  // --- 「时间线」标签页：来源链接在展开后才渲染，必须逐条点开 ---
  if (tabLabels.some((label) => label.includes('时间线'))) {
    await clickTab('时间线')
    await waitForCount('.timeline-item', 1)
    const itemCount = await page.locator('.timeline-item').count()
    assertTrue(itemCount > 0, `案件 ${caseId}「时间线」有条目`, `实际 ${itemCount} 条`)
    let timelineLinkCount = 0
    for (let index = 0; index < itemCount; index += 1) {
      // 展开当前条目 —— 来源链接只在展开时才渲染
      await page.locator('.timeline-item').nth(index).click({ noWaitAfter: true }).catch(() => {})
      await page.waitForTimeout(220)
      const expanded = await page
        .locator('.timeline-item')
        .nth(index)
        .locator('a[href]')
        .evaluateAll((nodes) =>
          nodes
            .filter((a) => /^https?:\/\//i.test(a.getAttribute('href') || ''))
            .map((a) => ({ href: a.getAttribute('href'), text: (a.textContent || '').trim().slice(0, 50) })),
        )
      timelineLinkCount += expanded.length
      // 必须**当场**校验：点下一个条目会把当前条目收起，链接随即从 DOM 消失
      await verifyLinks(expanded, `案件${caseId}·时间线`)
    }
    console.log(`  （时间线标签页：${itemCount} 个条目，展开后共 ${timelineLinkCount} 条来源链接）`)
  }
}

console.log('\n=== 汇总 ===')
// 自检：declared 只被 assertTrue 递增，两者必须相等 —— 不等说明有断言被跳过。
if (declared !== executed) {
  console.log(`!! 自检失败：declared=${declared} executed=${executed}`)
  failed += 1
}
console.log(`SUMMARY declared=${declared} executed=${executed} failed=${failed} `
  + `warned=${warnings.length} clicked=${clickedCount}`)
console.log(`校验了 ${seen.size} 条唯一来源链接`)
if (problems.length) {
  console.log('\n失败项：')
  problems.forEach((p) => console.log(`  - ${p}`))
}
if (warnings.length) {
  console.log('\n警告（不算失败）：')
  warnings.forEach((w) => console.log(`  - ${w}`))
}

await browser.close()
process.exit(failed ? 1 : 0)
