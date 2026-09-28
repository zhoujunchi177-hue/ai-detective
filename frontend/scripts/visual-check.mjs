import fs from 'node:fs/promises'
import path from 'node:path'
import { chromium } from 'playwright-core'

const browserPath =
  process.env.BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const baseUrl = process.env.MINDTRACE_URL || 'http://127.0.0.1:5173'
// 账号可通过环境变量指定，便于用「全部解锁」和「部分解锁」两个账号分别验证徽章样式。
const username = process.env.MINDTRACE_USER || 'demo_investigator'
const password = process.env.MINDTRACE_PASS || 'demo123'
const outputDir = path.resolve('..', 'artifacts')
await fs.mkdir(outputDir, { recursive: true })

const browser = await chromium.launch({
  executablePath: browserPath,
  headless: true,
})

const page = await browser.newPage({
  viewport: { width: 1440, height: 1000 },
  deviceScaleFactor: 1,
})

const consoleErrors = []
page.on('console', (message) => {
  if (message.type() === 'error') {
    consoleErrors.push(message.text())
  }
})
page.on('pageerror', (error) => consoleErrors.push(error.message))

async function inspectOverflow(label) {
  return page.evaluate((name) => ({
    label: name,
    viewportWidth: window.innerWidth,
    documentWidth: document.documentElement.scrollWidth,
    horizontalOverflow: document.documentElement.scrollWidth > window.innerWidth + 1,
  }), label)
}

/**
 * 滚动进入动画会让「还没进视口」的元素停在 opacity:0，
 * 而 fullPage 截图走的是 captureBeyondViewport（不会真的滚动），
 * 于是截图里会出现一大片空白。截图前先把整页滚一遍，让所有元素都播完。
 * 这里是真实滚动，不是直接改样式 —— 顺便也当作一次滚动回归。
 */
async function settleReveals() {
  await page.evaluate(async () => {
    const step = Math.max(200, Math.floor(window.innerHeight * 0.75))
    for (let y = 0; y < document.documentElement.scrollHeight; y += step) {
      window.scrollTo(0, y)
      await new Promise((resolve) => setTimeout(resolve, 130))
    }
    window.scrollTo(0, 0)
    // 动画时长 900ms + 最大错峰延迟 220ms，留足余量
    await new Promise((resolve) => setTimeout(resolve, 1300))
  })
}

await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' })
await page.screenshot({ path: path.join(outputDir, '01-login.png'), fullPage: true })

await page.getByPlaceholder('输入用户名').fill(username)
await page.getByPlaceholder('输入密码').fill(password)
await page.getByRole('button', { name: '进入档案系统' }).click()
await page.waitForURL('**/home')
await page.waitForLoadState('networkidle')
await settleReveals()
await page.screenshot({ path: path.join(outputDir, '02-home.png'), fullPage: true })
const homeOverflow = await inspectOverflow('home-desktop')

// 滚动进入动画：核心契约是「没进视口就不显示，进了才显示」。
// 用矮视口把 .rules-grid 挤到折叠线以下，才能稳定复现「尚未进入视口」的状态。
await page.setViewportSize({ width: 1440, height: 700 })
await page.goto(`${baseUrl}/home`, { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
const revealBefore = await page.evaluate(() => {
  const nodes = Array.from(document.querySelectorAll('.rules-grid article'))
  const belowFold = nodes.filter((node) => node.getBoundingClientRect().top > window.innerHeight)
  return {
    total: nodes.length,
    belowFold: belowFold.length,
    belowHasRevealClass: belowFold.every((node) => node.classList.contains('reveal')),
    belowHidden: belowFold.every((node) => Number(getComputedStyle(node).opacity) < 0.05),
    belowNotMarkedVisible: belowFold.every((node) => !node.classList.contains('reveal--visible')),
    transitionDuration: nodes.length ? getComputedStyle(nodes[0]).transitionDuration : '',
  }
})

// 滚到最后一个（延迟 220ms 的那张）
await page.evaluate(() => {
  const nodes = document.querySelectorAll('.rules-grid article')
  nodes[nodes.length - 1]?.scrollIntoView({ block: 'center' })
})
await page.waitForTimeout(1500)
await page.screenshot({ path: path.join(outputDir, '19-scroll-reveal.png') })
const revealAfter = await page.evaluate(() => {
  const nodes = Array.from(document.querySelectorAll('.rules-grid article'))
  const last = nodes[nodes.length - 1]
  return {
    visibleCount: nodes.filter((node) => node.classList.contains('reveal--visible')).length,
    lastMarkedVisible: last?.classList.contains('reveal--visible') ?? false,
    lastOpacity: last ? Number(getComputedStyle(last).opacity) : 0,
    lastDelay: last?.style.getPropertyValue('--reveal-delay') ?? '',
  }
})

// 无障碍：系统开启「减少动态效果」时，内容必须立刻可见，不能卡在 opacity:0
await page.emulateMedia({ reducedMotion: 'reduce' })
await page.goto(`${baseUrl}/home`, { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
const revealReducedMotion = await page.evaluate(() => {
  const nodes = Array.from(document.querySelectorAll('.rules-grid article'))
  return {
    count: nodes.length,
    allVisible: nodes.every((node) => Number(getComputedStyle(node).opacity) > 0.99),
    allMarked: nodes.every((node) => node.classList.contains('reveal--visible')),
  }
})
await page.emulateMedia({ reducedMotion: 'no-preference' })
await page.setViewportSize({ width: 1440, height: 1000 })

await page.goto(`${baseUrl}/cases`, { waitUntil: 'networkidle' })
await settleReveals()
await page.screenshot({ path: path.join(outputDir, '03-cases.png'), fullPage: true })
const casesOverflow = await inspectOverflow('cases-desktop')

// 案件列表的「存档进度」：上面那条是案件本身的资料完整度，下面这条是这个玩家自己的存档。
// 两者必须分开渲染，否则玩家会把 86% 当成自己的进度。
const demoListState = await page.evaluate(() => {
  const cards = Array.from(document.querySelectorAll('.case-grid .case-card'))
  return {
    cardCount: cards.length,
    cards: cards.map((card) => ({
      progressLabel: (card.querySelector('.progress-head span')?.textContent || '').trim(),
      percentText: (card.querySelector('.progress-head strong')?.textContent || '').trim(),
      action: (card.querySelector('.enter-case')?.textContent || '').replace(/\s+/g, ' ').trim(),
      detail: (card.querySelector('.progress-detail')?.textContent || '').replace(/\s+/g, ' ').trim(),
      completed: Boolean(card.querySelector('.progress-block.is-completed')),
      // 资料完整度轨道与玩家进度轨道各一条，不能合并成同一条
      separateTracks:
        card.querySelectorAll('.completion-track').length === 1 &&
        card.querySelectorAll('.progress-track').length === 1,
    })),
  }
})

await page.goto(`${baseUrl}/case/1`, { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
await page.screenshot({ path: path.join(outputDir, '04-case-desktop.png'), fullPage: true })
const caseOverflow = await inspectOverflow('case-desktop')

// 「日志」标签页：验证调查历史能渲染，且不会把 AI 原始 JSON 泄露到界面。
await page.locator('nav.case-tabs button').filter({ hasText: '日志' }).click()
await page.waitForTimeout(800)
await page.screenshot({ path: path.join(outputDir, '06-case-log.png'), fullPage: true })
const logOverflow = await inspectOverflow('case-log')
const logState = await page.evaluate(() => {
  const rows = Array.from(document.querySelectorAll('.log-list > li'))
  return {
    entryCount: rows.length,
    rawJsonLeaked: rows.some((row) => (row.textContent || '').includes('"summary"')),
    chips: Array.from(
      new Set(Array.from(document.querySelectorAll('.log-chip')).map((el) => (el.textContent || '').trim())),
    ),
    firstRow: rows.length ? (rows[0].textContent || '').replace(/\s+/g, ' ').trim().slice(0, 90) : null,
  }
})

// 日志筛选：类型筛选与关键词筛选都必须真的改变列表，而不只是改个高亮样式。
//
// 筛选已经移到服务端：点击/输入不再是「立即生效」—— 关键词有 300ms 防抖，
// 之后还有一次网络往返。原先那种「点完等 250ms 再读 DOM」在慢机器上会读到**上一轮**
// 的结果，而读到的行数和类型标签看起来都完全合理，是典型的静默假通过。
// 所以这里不再猜时间，而是等 /history 真的回了一次响应。
async function triggerHistoryQuery(action) {
  const responded = page.waitForResponse(
    (res) => res.url().includes('/history') && res.request().method() === 'GET',
    { timeout: 20000 },
  )
  await action()
  await responded
  // 响应回来后 Vue 还要渲染一帧；等 loading 态收掉，DOM 才与数据一致。
  await page.waitForFunction(() => !document.querySelector('.log-loading'), null, { timeout: 10000 })
}

const logFilterState = { types: {}, keyword: null, noMatch: null, afterReset: null, range: {}, pager: null }

for (const label of ['调查地点', '推理分析', '谜题校验']) {
  await triggerHistoryQuery(() =>
    page.locator('.log-type-tabs button').filter({ hasText: label }).click(),
  )
  logFilterState.types[label] = await page.evaluate(() => ({
    rows: document.querySelectorAll('.log-list > li').length,
    chipLabels: Array.from(
      new Set(
        Array.from(document.querySelectorAll('.log-list .log-chip')).map((el) =>
          (el.textContent || '').trim(),
        ),
      ),
    ),
    noMatchShown: Boolean(document.querySelector('.log-no-match')),
    summary: (document.querySelector('.log-summary')?.textContent || '').replace(/\s+/g, ' ').trim(),
  }))
}

await triggerHistoryQuery(() =>
  page.locator('.log-type-tabs button').filter({ hasText: '全部' }).click(),
)
await triggerHistoryQuery(() => page.locator('.log-search input').fill('电梯'))
await page.screenshot({ path: path.join(outputDir, '11-log-filtered.png'), fullPage: true })
logFilterState.keyword = await page.evaluate(() => ({
  rows: document.querySelectorAll('.log-list > li').length,
  summary: (document.querySelector('.log-summary')?.textContent || '').replace(/\s+/g, ' ').trim(),
}))

await triggerHistoryQuery(() =>
  page.locator('.log-search input').fill('绝对不存在的关键词zzz'),
)
await page.screenshot({ path: path.join(outputDir, '12-log-no-match.png'), fullPage: true })
logFilterState.noMatch = await page.evaluate(() => ({
  rows: document.querySelectorAll('.log-list > li').length,
  noMatchShown: Boolean(document.querySelector('.log-no-match')),
}))

await triggerHistoryQuery(() => page.locator('.log-no-match button').click())
logFilterState.afterReset = await page.evaluate(() => ({
  rows: document.querySelectorAll('.log-list > li').length,
  keyword: document.querySelector('.log-search input')?.value ?? null,
}))

// 日期范围：填一个完全落在未来的区间，结果必须为空。
// 若 from/to 被后端忽略（例如漏了下推），这里会返回全部记录，断言立刻失败 ——
// 这比「今天~今天」可靠，因为库里记录的产生时间不确定。
//
// 同时验证两个输入框的取值**留得住**：早先用 computed 桥接原生 date 输入时，
// 「只填了一端」的中间状态无处存放，setter 只能不更新，v-model 随即把输入框回滚成空 ——
// 表现为「日期填进去就自己消失了」，而页面本身看起来毫无异常。
await triggerHistoryQuery(async () => {
  await page.locator('.log-range input[type="date"]').first().fill('2099-01-01')
  await page.locator('.log-range input[type="date"]').last().fill('2099-12-31')
})
await page.screenshot({ path: path.join(outputDir, '22-log-date-range.png'), fullPage: true })
logFilterState.range.future = await page.evaluate(() => ({
  rows: document.querySelectorAll('.log-list > li').length,
  noMatchShown: Boolean(document.querySelector('.log-no-match')),
  from: document.querySelector('.log-range input[type="date"]')?.value ?? null,
  clearButtonShown: Boolean(document.querySelector('.log-clear')),
}))

await triggerHistoryQuery(() => page.locator('.log-clear').click())
logFilterState.range.afterClear = await page.evaluate(() => ({
  rows: document.querySelectorAll('.log-list > li').length,
  from: document.querySelector('.log-range input[type="date"]')?.value ?? null,
}))

// 原生日期控件的配色：color-scheme 必须显式声明成 dark，
// 否则浏览器给日期弹层用亮色配色，在暗色界面里会掉出一块白底。
logFilterState.range.colorScheme = await page.evaluate(() => {
  const input = document.querySelector('.log-range input[type="date"]')
  return input ? getComputedStyle(input).colorScheme : null
})

// 分页控件只在超过一页时出现。demo 账号只有个位数日志，这里应当**不出现** ——
// 若它无条件渲染出来，玩家会看到一个「1 / 1」的假分页。
// 反方向（记录多于一页时确实出现）由 verify-api.ps1 的 size=1 / hasMore 断言覆盖。
logFilterState.pager = await page.evaluate(() => ({
  shown: Boolean(document.querySelector('.log-pager')),
  rows: document.querySelectorAll('.log-list > li').length,
}))

// 导出：真的点一次下载，并检查文件内容与当前筛选一致。
// 「点了没反应」或「导出内容与界面不符」都只有读到文件才发现得了。
await triggerHistoryQuery(() =>
  page.locator('.log-type-tabs button').filter({ hasText: '推理分析' }).click(),
)
const [download] = await Promise.all([
  page.waitForEvent('download'),
  page.locator('.log-action').filter({ hasText: '导出笔记' }).click(),
])
const downloadPath = await download.path()
const downloadText = await fs.readFile(downloadPath, 'utf8')
logFilterState.export = {
  suggestedFilename: download.suggestedFilename(),
  bytes: Buffer.byteLength(downloadText, 'utf8'),
  headingCount: (downloadText.match(/^## /gm) || []).length,
  visibleRows: logFilterState.types['推理分析'].rows,
  hasTitle: downloadText.startsWith('# 调查日志'),
  hasRealityNotice: downloadText.includes('REAL / ADAPTED / FICTIONAL'),
}
await triggerHistoryQuery(() =>
  page.locator('.log-type-tabs button').filter({ hasText: '全部' }).click(),
)

// 「地点」标签页：节点状态必须来自后端，前端只渲染。
// 这里先看一个已完成案件的账号：所有节点都应该是 COMPLETED / INVESTIGATED。
await page.goto(`${baseUrl}/case/1`, { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
await page.locator('nav.case-tabs button').filter({ hasText: '地点' }).click()
await page.waitForTimeout(600)
await page.screenshot({ path: path.join(outputDir, '13-case-map-nodes.png'), fullPage: true })
const mapOverflow = await inspectOverflow('case-map')
const mapState = await page.evaluate(() => {
  const statusOf = (el) => {
    const match = Array.from(el.classList).find((cls) => cls.startsWith('map-node--'))
    return match ? match.replace('map-node--', '') : 'NONE'
  }
  const nodes = Array.from(document.querySelectorAll('.location-map .map-node'))
  const active = document.querySelector('.location-map .map-node.active')
  const badge = document.querySelector('.location-detail .node-badge')
  return {
    nodeCount: nodes.length,
    statuses: nodes.map(statusOf),
    clueBadges: nodes.filter((node) => node.querySelector('em')).length,
    activeStatus: active ? statusOf(active) : null,
    badgeText: (badge?.textContent || '').trim(),
    badgeClass: badge
      ? Array.from(badge.classList).find((cls) => cls.startsWith('node-badge--')) || null
      : null,
    metricsRows: document.querySelectorAll('.location-detail .location-metrics > div').length,
  }
})

// 「证据链」标签页：连线是玩家自己的推理产物，必须真的能建、能看到、能删。
await page.locator('nav.case-tabs button').filter({ hasText: '证据链' }).click()
await page.waitForTimeout(800)
await page.screenshot({ path: path.join(outputDir, '15-case-evidence-board.png'), fullPage: true })
const boardOverflow = await inspectOverflow('case-evidence-board')

const boardState = await page.evaluate(() => {
  const selects = Array.from(document.querySelectorAll('.board-form select'))
  return {
    nodeCount: document.querySelectorAll('.board-canvas .board-node').length,
    lineCount: document.querySelectorAll('.board-lines line').length,
    linkRows: document.querySelectorAll('.board-links > li').length,
    selectCount: selects.length,
    relationOptionCount: selects[1]?.options.length ?? 0,
    submitDisabled: document.querySelector('.board-submit')?.disabled ?? null,
    realityNoticeShown: (document.querySelector('.board-head p')?.textContent || '').includes('不是案件事实'),
  }
})

// 建一条关联：用下拉框选两端，填备注，提交。
const boardSelects = page.locator('.board-form select')
await boardSelects.nth(0).selectOption({ index: 1 })
await boardSelects.nth(2).selectOption({ index: 2 })
await page.locator('.board-form input').fill('验收脚本创建的关联')
await page.waitForTimeout(250)
const submitEnabledAfterPick = await page.locator('.board-submit').isEnabled()
await page.locator('.board-submit').click()
await page.waitForTimeout(1200)
await page.screenshot({ path: path.join(outputDir, '16-case-evidence-link.png'), fullPage: true })

const boardAfterCreate = await page.evaluate(() => ({
  linkRows: document.querySelectorAll('.board-links > li').length,
  lineCount: document.querySelectorAll('.board-lines line').length,
  noteShown: (document.querySelector('.board-links')?.textContent || '').includes('验收脚本创建的关联'),
  relationLabelShown: Boolean(document.querySelector('.board-link-text em')),
}))

// 删掉刚建的那条（按备注定位，避免误删别的连线）。
await page
  .locator('.board-links > li')
  .filter({ hasText: '验收脚本创建的关联' })
  .locator('.board-link-delete')
  .click()
await page.waitForTimeout(400)
await page.locator('.el-message-box__btns button').filter({ hasText: '删除' }).click()
await page.waitForTimeout(1000)

const boardAfterDelete = await page.evaluate(() => ({
  linkRows: document.querySelectorAll('.board-links > li').length,
  lineCount: document.querySelectorAll('.board-lines line').length,
  noteGone: !(document.querySelector('.board-links')?.textContent || '').includes('验收脚本创建的关联'),
}))

// 成就页：验证进度环、已解锁/未解锁两种卡片样式，以及筛选切换。
await page.goto(`${baseUrl}/achievements`, { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
await settleReveals()
await page.screenshot({ path: path.join(outputDir, '08-achievements.png'), fullPage: true })
const achievementsOverflow = await inspectOverflow('achievements-desktop')
const achievementState = await page.evaluate(() => {
  const cards = Array.from(document.querySelectorAll('.badge-card'))
  return {
    totalCards: cards.length,
    unlockedCards: cards.filter((card) => !card.classList.contains('locked')).length,
    lockedCards: cards.filter((card) => card.classList.contains('locked')).length,
    percent: (document.querySelector('.vault-ring-inner strong')?.textContent || '').trim(),
    rewardShown: cards.every((card) => /EXP/.test(card.textContent || '')),
  }
})

await page.locator('.vault-tabs button').filter({ hasText: '未解锁' }).click()
await page.waitForTimeout(400)
await settleReveals()
await page.screenshot({ path: path.join(outputDir, '09-achievements-locked.png'), fullPage: true })
const lockedFilterState = await page.evaluate(() => ({
  cards: document.querySelectorAll('.badge-card').length,
  emptyShown: Boolean(document.querySelector('.vault-empty')),
}))

await page.locator('.vault-tabs button').filter({ hasText: '全部徽章' }).click()
await page.waitForTimeout(300)

// 稀有度：每张徽章卡都要有稀有度标签，且只能是四档之一。
// 稀有度板块的格子数要与实际存在的档位一致（不能凭空多出没有徽章的档）。
const rarityState = await page.evaluate(() => {
  const validLabels = ['常见', '稀有', '史诗', '传说']
  const cards = Array.from(document.querySelectorAll('.badge-card'))
  const labels = cards.map((card) => (card.querySelector('.rarity')?.textContent || '').trim())
  const tiersInCards = new Set(labels.filter((label) => validLabels.includes(label)))
  return {
    totalCards: cards.length,
    cardsWithRarity: cards.filter((card) => card.querySelector('.rarity')).length,
    allLabelsValid: labels.length > 0 && labels.every((label) => validLabels.includes(label)),
    tiersInCards: Array.from(tiersInCards),
    boardCells: document.querySelectorAll('.rarity-cell').length,
    boardNote: (document.querySelector('.rarity-note')?.textContent || '').replace(/\s+/g, ' ').trim(),
  }
})
await page.screenshot({ path: path.join(outputDir, '20-achievements-rarity.png'), fullPage: true })

// 分享图：点开 → 预览必须是一张真的 1200×720 图，不是空白 canvas →
// 下载下来的文件必须带 PNG 签名，且尺寸与预览一致。
// 「点了没反应」和「导出一张空白图」都只有真的读文件才发现得了。
await page.locator('.vault-share').click()
await page.locator('.share-preview, .share-error').first().waitFor({ timeout: 15000 })
await page.waitForTimeout(600)
const sharePreview = await page.evaluate(() => {
  const img = document.querySelector('.share-preview')
  return {
    shown: Boolean(img),
    naturalWidth: img?.naturalWidth ?? 0,
    naturalHeight: img?.naturalHeight ?? 0,
    error: (document.querySelector('.share-error')?.textContent || '').trim(),
    meta: (document.querySelector('.share-meta')?.textContent || '').replace(/\s+/g, ' ').trim(),
  }
})
await page.screenshot({ path: path.join(outputDir, '21-achievements-share.png'), fullPage: true })

const [cardDownload] = await Promise.all([
  page.waitForEvent('download'),
  page.locator('.share-actions button').filter({ hasText: '下载 PNG' }).click(),
])
const cardBuffer = await fs.readFile(await cardDownload.path())
const shareState = {
  ...sharePreview,
  suggestedFilename: cardDownload.suggestedFilename(),
  bytes: cardBuffer.length,
  // PNG 魔数：89 50 4E 47 0D 0A 1A 0A
  pngSignature: cardBuffer.subarray(0, 8).toString('hex') === '89504e470d0a1a0a',
  // IHDR 紧跟在签名之后，宽高是第 16..23 字节的大端 uint32
  width: cardBuffer.readUInt32BE(16),
  height: cardBuffer.readUInt32BE(20),
}
await page.locator('.el-dialog__headerbtn').click()
await page.waitForTimeout(400)

// 声音面板：BGM 与音效必须真正互相独立 —— 调一个不能动另一个，
// 一键静音也不能顺手把滑块清掉（恢复时要还是原来的响度）。
// 滑块用键盘方向键操作，是真实的用户输入，而不是直接改 DOM 值。
await page.locator('.audio-settings-button').click()
await page.locator('.audio-panel').waitFor({ timeout: 5000 })
await page.screenshot({ path: path.join(outputDir, '18-audio-panel.png'), fullPage: true })

const readAudioPanel = () =>
  page.evaluate(() => ({
    bgmPercent: (document.querySelector('.audio-percent--bgm')?.textContent || '').trim(),
    sfxPercent: (document.querySelector('.audio-percent--sfx')?.textContent || '').trim(),
    bgmPressed: document.querySelector('.audio-toggle--bgm-row')?.getAttribute('aria-pressed'),
    sfxPressed: document.querySelector('.audio-toggle--sfx-row')?.getAttribute('aria-pressed'),
    muteLabel: (document.querySelector('.audio-mute-all')?.textContent || '').replace(/\s+/g, ' ').trim(),
  }))

const audioInitial = await readAudioPanel()
const audioSliderCount = await page.locator('.audio-panel .audio-slider').count()

await page.locator('.audio-slider--bgm').focus()
for (let i = 0; i < 3; i += 1) await page.keyboard.press('ArrowLeft')
await page.waitForTimeout(150)
const audioAfterBgm = await readAudioPanel()

await page.locator('.audio-slider--sfx').focus()
for (let i = 0; i < 6; i += 1) await page.keyboard.press('ArrowRight')
await page.waitForTimeout(150)
const audioAfterSfx = await readAudioPanel()

await page.locator('.audio-toggle--sfx-row').click()
await page.waitForTimeout(150)
const audioSfxOff = await readAudioPanel()

await page.locator('.audio-mute-all').click()
await page.waitForTimeout(150)
const audioAllMuted = await readAudioPanel()

await page.locator('.audio-mute-all').click()
await page.waitForTimeout(150)
const audioRestored = await readAudioPanel()

// 刷新后设置必须还在（localStorage 持久化）
await page.reload({ waitUntil: 'networkidle' })
await page.waitForTimeout(500)
const audioStored = await page.evaluate(() => ({
  bgmVolume: localStorage.getItem('mindtrace_bgm_volume'),
  sfxVolume: localStorage.getItem('mindtrace_sfx_volume'),
  bgmOn: localStorage.getItem('mindtrace_bgm'),
  sfxOn: localStorage.getItem('mindtrace_sfx'),
}))

await page.setViewportSize({ width: 390, height: 844 })
await page.goto(`${baseUrl}/case/1`, { waitUntil: 'networkidle' })
await page.waitForTimeout(500)
await page.screenshot({ path: path.join(outputDir, '05-case-mobile.png'), fullPage: true })
const mobileOverflow = await inspectOverflow('case-mobile')

await page.locator('nav.case-tabs button').filter({ hasText: '日志' }).click()
await page.waitForTimeout(700)
await page.screenshot({ path: path.join(outputDir, '07-case-log-mobile.png'), fullPage: true })
const logMobileOverflow = await inspectOverflow('case-log-mobile')

await page.goto(`${baseUrl}/achievements`, { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
await settleReveals()
await page.screenshot({ path: path.join(outputDir, '10-achievements-mobile.png'), fullPage: true })
const achievementsMobileOverflow = await inspectOverflow('achievements-mobile')

// 新账号：验证 LOCKED 节点在界面上确实不可进入，并且说明了解锁条件。
// 只有真的走一遍注册 + 打开案件，才能确认前端没有绕过后端的状态判定。
// 注意：/register 是 guest 路由，已登录访问会被重定向回 /home，所以必须先清掉本地登录态。
await page.setViewportSize({ width: 1440, height: 1000 })
await page.evaluate(() => {
  localStorage.removeItem('mindtrace_token')
  localStorage.removeItem('mindtrace_profile')
})
const freshUser = `uicheck${Date.now()}`
const freshPassword = 'uicheck12345'
await page.goto(`${baseUrl}/register`, { waitUntil: 'networkidle' })
await page.getByPlaceholder('3-20 位字母数字').fill(freshUser)
await page.getByPlaceholder('排行榜显示名称').fill('界面校验')
const passwordInputs = page.locator('input[type="password"]')
await passwordInputs.nth(0).fill(freshPassword)
await passwordInputs.nth(1).fill(freshPassword)
await page.getByRole('button', { name: '创建档案并进入档案馆' }).click()
await page.waitForURL('**/home')
await page.goto(`${baseUrl}/case/1`, { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
await page.locator('nav.case-tabs button').filter({ hasText: '地点' }).click()
await page.waitForTimeout(600)
await page.screenshot({ path: path.join(outputDir, '14-case-locked-map.png'), fullPage: true })
const lockedOverflow = await inspectOverflow('case-locked-map')

const lockedState = await page.evaluate(() => {
  const nodes = Array.from(document.querySelectorAll('.location-map .map-node'))
  const lockedNodes = nodes.filter((node) => node.classList.contains('map-node--locked'))
  const active = document.querySelector('.location-map .map-node.active')
  return {
    totalNodes: nodes.length,
    lockedCount: lockedNodes.length,
    availableCount: nodes.filter((node) => node.classList.contains('map-node--available')).length,
    // 未解锁节点不该显示线索计数（那会泄露还没拿到的内容）
    lockedHasNoClueBadge: lockedNodes.every((node) => !node.querySelector('em')),
    lockedMarkedAriaDisabled: lockedNodes.every(
      (node) => node.getAttribute('aria-disabled') === 'true',
    ),
    activeIsNotLocked: Boolean(active) && !active.classList.contains('map-node--locked'),
  }
})

// 点一个未解锁节点：必须不切换选中项，并且弹出解锁原因。
// 用 force 点击：节点用的是 aria-disabled 而不是原生 disabled（真实用户点得动，
// 也正因为点得动才需要看到「为什么不能进」的提示），Playwright 默认会拦下这种元素。
const beforeActive = await page.evaluate(
  () => (document.querySelector('.location-map .map-node.active')?.textContent || '').replace(/\s+/g, ' ').trim(),
)
await page.locator('.location-map .map-node--locked').first().click({ force: true })
await page.waitForTimeout(450)
const lockedClickState = await page.evaluate(() => ({
  active: (document.querySelector('.location-map .map-node.active')?.textContent || '')
    .replace(/\s+/g, ' ')
    .trim(),
  warningShown: Boolean(document.querySelector('.el-message')),
}))
lockedClickState.activeUnchanged = lockedClickState.active === beforeActive

// 存档进度：新账号调查过一个地点之后，列表页必须从「开始调查」变成「继续调查」。
// 这一步同时验证了进度是后端算的 —— 前端只负责把 percent 显示出来。
await page.locator('.investigate-button').click()
await page.locator('.investigation-result').waitFor({ timeout: 20000 })
await page.waitForTimeout(300)

await page.goto(`${baseUrl}/cases`, { waitUntil: 'networkidle' })
await page.waitForTimeout(400)
await settleReveals()
await page.screenshot({ path: path.join(outputDir, '17-cases-progress.png'), fullPage: true })
const freshListState = await page.evaluate(() => {
  const cards = Array.from(document.querySelectorAll('.case-grid .case-card'))
  return {
    cardCount: cards.length,
    cards: cards.map((card) => ({
      progressLabel: (card.querySelector('.progress-head span')?.textContent || '').trim(),
      percentText: (card.querySelector('.progress-head strong')?.textContent || '').trim(),
      action: (card.querySelector('.enter-case')?.textContent || '').replace(/\s+/g, ' ').trim(),
      detail: (card.querySelector('.progress-detail')?.textContent || '').replace(/\s+/g, ' ').trim(),
      completed: Boolean(card.querySelector('.progress-block.is-completed')),
    })),
  }
})

// 逐卡配对：按钮文案必须和进度标签一致。这里刻意不假设「哪一案已通关」——
// 测试账号的调查记录会随着每轮跑测试而增长，写死下标迟早误报。
const demoFinishedCards = demoListState.cards.filter((card) => card.progressLabel === '已完成')
const demoUntouchedCards = demoListState.cards.filter((card) => card.progressLabel === '未开始')
const freshStartedCard = freshListState.cards.find((card) => card.progressLabel === '调查中')
const freshUntouchedCards = freshListState.cards.filter((card) => card.progressLabel === '未开始')
/** "25%" -> 0.25。面板上显示的是百分比文本，存的是 0..1 的小数，比较前要先换算。 */
const percentToRatio = (text) => Number(String(text).replace('%', '')) / 100

const revealState = {
  before: revealBefore,
  after: revealAfter,
  reducedMotion: revealReducedMotion,
}

const audioState = {
  sliderCount: audioSliderCount,
  initial: audioInitial,
  afterBgm: audioAfterBgm,
  afterSfx: audioAfterSfx,
  sfxOff: audioSfxOff,
  allMuted: audioAllMuted,
  restored: audioRestored,
  stored: audioStored,
}

const result = {  browserPath,
  username,
  checkedAt: new Date().toISOString(),
  consoleErrors,
  logState,
  logFilterState,
  demoListState,
  audioState,
  revealState,
  mapState,
  lockedState,
  lockedClickState,
  freshListState,
  boardState,
  boardAfterCreate,
  boardAfterDelete,
  achievementState,
  rarityState,
  shareState,
  lockedFilterState,
  overflow: [
    homeOverflow,
    casesOverflow,
    caseOverflow,
    logOverflow,
    mapOverflow,
    boardOverflow,
    achievementsOverflow,
    mobileOverflow,
    logMobileOverflow,
    achievementsMobileOverflow,
    lockedOverflow,
  ],
  screenshots: [
    '01-login.png',
    '02-home.png',
    '03-cases.png',
    '04-case-desktop.png',
    '05-case-mobile.png',
    '06-case-log.png',
    '07-case-log-mobile.png',
    '08-achievements.png',
    '09-achievements-locked.png',
    '10-achievements-mobile.png',
    '11-log-filtered.png',
    '12-log-no-match.png',
    '22-log-date-range.png',
    '13-case-map-nodes.png',
    '14-case-locked-map.png',
    '15-case-evidence-board.png',
    '16-case-evidence-link.png',
    '20-achievements-rarity.png',
    '21-achievements-share.png',
  ],
}

// 断言而不是只打印 JSON：这样脚本可以直接用于 CI，也能避免「看一眼截图就以为没问题」。
result.checks = {
  noConsoleErrors: consoleErrors.length === 0,
  noHorizontalOverflow: result.overflow.every((item) => !item.horizontalOverflow),
  logDoesNotLeakRawJson: !logState.rawJsonLeaked,
  // 每个类型筛选后，列表里的类型标签最多只能有一种（0 条时为空，也算通过）。
  logTypeFilterIsolatesTypes: Object.values(logFilterState.types).every(
    (state) => state.chipLabels.length <= 1,
  ),
  logKeywordNarrowsList:
    logFilterState.keyword.rows > 0 && logFilterState.keyword.rows < logState.entryCount,
  logNonsenseKeywordShowsEmptyState:
    logFilterState.noMatch.rows === 0 && logFilterState.noMatch.noMatchShown,
  logClearFilterRestoresAll: logFilterState.afterReset.rows === logState.entryCount,
  // 日期范围：未来区间必须筛成空。若 from/to 被后端忽略，这里会拿到全部记录。
  logDateRangeFiltersToNothing:
    logFilterState.range.future.rows === 0 && logFilterState.range.future.noMatchShown,
  // 输入框的取值必须留得住 —— 早先 computed 桥接的实现会让日期填完自己消失，
  // 而页面看起来完全正常，只有断言能发现。
  logDateRangeInputKeepsItsValue: logFilterState.range.future.from === '2099-01-01',
  logClearRestoresAfterDateRange: logFilterState.range.afterClear.rows === logState.entryCount,
  // 原生日期弹层的配色必须跟随暗色主题，否则会掉出一块白底。
  logDateInputUsesDarkColorScheme: (logFilterState.range.colorScheme || '').includes('dark'),
  // 只有一页时不该出现分页控件（会出现一个「1 / 1」的假分页）。
  logPagerHiddenWhenSinglePage: !logFilterState.pager.shown,
  // 导出必须真的产出文件，且内容条数与当前筛选结果一致（这里筛选的是「推理分析」）。
  logExportMatchesFilter:
    logFilterState.export.headingCount === logFilterState.export.visibleRows &&
    logFilterState.export.hasTitle &&
    logFilterState.export.hasRealityNotice,
  achievementsRewardShown: achievementState.rewardShown,
  achievementsCardsMatchUnlockedCount:
    achievementState.totalCards === achievementState.unlockedCards + achievementState.lockedCards,
  lockedFilterMatchesLockedCards:
    lockedFilterState.cards === achievementState.lockedCards ||
    (achievementState.lockedCards === 0 && lockedFilterState.emptyShown),
  // 稀有度：每张卡都要有标签，且只能是四档之一；板块格子数不能超过实际存在的档位数。
  achievementsShowRarityOnEveryCard:
    rarityState.totalCards > 0 &&
    rarityState.cardsWithRarity === rarityState.totalCards &&
    rarityState.allLabelsValid,
  achievementsRarityBoardMatchesTiers:
    rarityState.boardCells === rarityState.tiersInCards.length && rarityState.boardCells > 0,
  // 分享图：预览必须是一张真的 1200×720 图，下载文件必须带 PNG 签名且尺寸一致。
  // 空白 canvas 也能「导出成功」，所以这里读文件头而不是只看有没有下载事件。
  shareCardPreviewIsRealImage:
    shareState.shown &&
    !shareState.error &&
    shareState.naturalWidth === 1200 &&
    shareState.naturalHeight === 720,
  shareCardDownloadIsPng:
    shareState.pngSignature &&
    shareState.width === 1200 &&
    shareState.height === 720 &&
    shareState.bytes > 5000,
  shareCardFilenameIsMeaningful: /^mindtrace-achievements-.+-\d{8}\.png$/.test(
    shareState.suggestedFilename,
  ),
  // 地图节点：状态由后端给出，前端只渲染；节点状态徽章必须与选中节点一致。
  // 类名后缀是小写的（map-node--completed），后端字段是大写的，这里统一大写比较。
  mapNodesExposeBackendStatus: mapState.statuses.every((status) =>
    ['LOCKED', 'AVAILABLE', 'INVESTIGATED', 'COMPLETED'].includes(status.toUpperCase()),
  ),
  mapRendersEveryLocation: mapState.nodeCount >= 4,
  mapShowsClueProgress: mapState.clueBadges > 0 && mapState.metricsRows === 2,
  mapDetailBadgeMatchesNodeState:
    mapState.activeStatus !== null &&
    mapState.badgeClass === `node-badge--${mapState.activeStatus}` &&
    mapState.badgeText.length > 0,
  // 新账号：未解锁节点必须可见、不可进入、不泄露线索数量。
  lockedNodesAreRendered: lockedState.lockedCount > 0 && lockedState.availableCount >= 2,
  lockedNodesHideClueCounts: lockedState.lockedHasNoClueBadge,
  lockedNodesMarkedAriaDisabled: lockedState.lockedMarkedAriaDisabled,
  freshAccountOpensOnAnUnlockedNode: lockedState.activeIsNotLocked,
  lockedNodeClickDoesNotSelectIt: lockedClickState.activeUnchanged,
  lockedNodeClickExplainsWhy: lockedClickState.warningShown,
  // 证据板：节点 = 已发现线索，连线 = 玩家自己建的关联，且必须真的能建能删。
  boardRendersDiscoveredClues: boardState.nodeCount >= 2,
  boardExposesRelationOptions: boardState.selectCount === 3 && boardState.relationOptionCount >= 2,
  boardStatesItIsPlayerReasoningNotCaseFact: boardState.realityNoticeShown,
  boardLinesMatchExistingLinks: boardState.lineCount === boardState.linkRows,
  boardSubmitNeedsBothEnds: boardState.submitDisabled === true && submitEnabledAfterPick === true,
  boardCreateAddsLink: boardAfterCreate.linkRows === boardState.linkRows + 1 && boardAfterCreate.noteShown,
  boardCreateDrawsLine: boardAfterCreate.lineCount === boardAfterCreate.linkRows,
  boardCreateShowsRelationLabel: boardAfterCreate.relationLabelShown,
  boardDeleteRemovesLink:
    boardAfterDelete.linkRows === boardState.linkRows &&
    boardAfterDelete.lineCount === boardState.linkRows &&
    boardAfterDelete.noteGone,
  // 存档进度：列表页要能区分「没开始 / 调查中 / 已完成」，并且按钮文案跟着变。
  casesShowPlayerProgress:
    demoListState.cardCount >= 3 &&
    demoListState.cards.every(
      (card) => card.progressLabel.length > 0 && /^\d+%$/.test(card.percentText),
    ),
  casesSeparateArchiveFromContentCompletion: demoListState.cards.every(
    (card) => card.separateTracks,
  ),
  // 逐卡配对，不依赖具体哪一案已通关
  caseActionMatchesProgressLabel: demoListState.cards.every((card) => {
    if (card.progressLabel === '未开始') {
      return (
        card.action === '开始调查' &&
        card.percentText === '0%' &&
        !card.completed &&
        card.detail === '还没有开始调查'
      )
    }
    if (card.progressLabel === '调查中') {
      return card.action === '继续调查' && !card.completed && card.percentText !== '0%'
    }
    if (card.progressLabel === '已完成') {
      return card.action === '查看结论' && card.completed
    }
    return false
  }),
  // 至少要同时出现「已开始」和「未开始」两种卡片，否则上面那条其实什么都没验证
  casesCoverBothStartedAndUntouched:
    demoFinishedCards.length >= 1 && demoUntouchedCards.length >= 1,
  finishedCaseOffersConclusion:
    demoFinishedCards.length >= 1 &&
    demoFinishedCards.every((card) => card.action === '查看结论' && card.completed === true),
  unstartedCasesOfferStart:
    demoUntouchedCards.length >= 1 &&
    demoUntouchedCards.every(
      (card) => card.action === '开始调查' && card.detail === '还没有开始调查',
    ),
  freshAccountListsUntouchedCasesAsStart:
    freshUntouchedCards.length >= 2 &&
    freshUntouchedCards.every((card) => card.action === '开始调查' && card.percentText === '0%'),
  freshAccountSwitchesToContinue:
    Boolean(freshStartedCard) &&
    freshStartedCard.action === '继续调查' &&
    freshStartedCard.progressLabel === '调查中',
  freshAccountProgressIsNonZero: Boolean(freshStartedCard) && /^[1-9]\d*%$/.test(freshStartedCard.percentText),
  freshAccountShowsProgressBreakdown:
    Boolean(freshStartedCard) &&
    /地点 \d+\/\d+/.test(freshStartedCard.detail) &&
    /线索 \d+\/\d+/.test(freshStartedCard.detail) &&
    /谜题 \d+\/\d+/.test(freshStartedCard.detail),
  // 声音面板：两条独立轨道（BGM / 音效），互不干扰。
  audioPanelExposesTwoIndependentSliders: audioState.sliderCount === 2,
  audioShowsBothPercentages:
    /^\d+%$/.test(audioState.initial.bgmPercent) &&
    /^\d+%$/.test(audioState.initial.sfxPercent),
  audioBgmSliderOnlyMovesBgm:
    audioState.afterBgm.bgmPercent !== audioState.initial.bgmPercent &&
    audioState.afterBgm.sfxPercent === audioState.initial.sfxPercent,
  audioSfxSliderOnlyMovesSfx:
    audioState.afterSfx.sfxPercent !== audioState.afterBgm.sfxPercent &&
    audioState.afterSfx.bgmPercent === audioState.afterBgm.bgmPercent,
  audioSfxToggleDoesNotTouchBgm:
    audioState.sfxOff.sfxPressed === 'false' &&
    audioState.sfxOff.bgmPressed === 'true' &&
    audioState.sfxOff.bgmPercent === audioState.afterSfx.bgmPercent,
  audioMuteAllTurnsBothOff:
    audioState.allMuted.bgmPressed === 'false' &&
    audioState.allMuted.sfxPressed === 'false' &&
    audioState.allMuted.muteLabel.includes('恢复声音'),
  // 关键：一键静音只翻开关，不能把滑块清掉
  audioMuteAllKeepsVolumes:
    audioState.allMuted.bgmPercent === audioState.afterSfx.bgmPercent &&
    audioState.allMuted.sfxPercent === audioState.afterSfx.sfxPercent,
  audioRestoreTurnsBothBackOn:
    audioState.restored.bgmPressed === 'true' &&
    audioState.restored.sfxPressed === 'true' &&
    audioState.restored.muteLabel.includes('全部静音'),
  audioSettingsPersistAcrossReload:
    audioState.stored.bgmVolume === String(percentToRatio(audioState.afterSfx.bgmPercent)) &&
    audioState.stored.sfxVolume === String(percentToRatio(audioState.afterSfx.sfxPercent)) &&
    audioState.stored.bgmOn === 'on' &&
    audioState.stored.sfxOn === 'on',
  // 滚动进入动画：契约是「没进视口就不显示，进了才显示」，而且只播一次。
  scrollRevealHidesBelowFold:
    revealState.before.belowFold >= 1 &&
    revealState.before.belowHasRevealClass &&
    revealState.before.belowHidden &&
    revealState.before.belowNotMarkedVisible,
  // 时长必须落在「慢动画」区间（600–1500ms），太快会显得廉价
  scrollRevealUsesSlowTiming: (() => {
    const seconds = Number.parseFloat(String(revealState.before.transitionDuration || '0'))
    return seconds >= 0.6 && seconds <= 1.5
  })(),
  scrollRevealPlaysOnScrollIntoView:
    revealState.after.lastMarkedVisible &&
    revealState.after.lastOpacity > 0.99 &&
    revealState.after.visibleCount >= 1,
  // 错峰：最后一张卡片的延迟应该是非零的毫秒值
  scrollRevealAppliesStaggerDelay:
    /^\d+(\.\d+)?ms$/.test(revealState.after.lastDelay) &&
    Number.parseFloat(revealState.after.lastDelay) > 0,
  // 无障碍：prefers-reduced-motion 下内容必须立刻可见，不能卡在 opacity:0
  reducedMotionShowsContentImmediately:
    revealState.reducedMotion.count > 0 &&
    revealState.reducedMotion.allVisible &&
    revealState.reducedMotion.allMarked,
}

const failed = Object.entries(result.checks)
  .filter(([, passed]) => !passed)
  .map(([name]) => name)
result.failedChecks = failed

await fs.writeFile(
  path.join(outputDir, 'visual-check.json'),
  JSON.stringify(result, null, 2),
  'utf8',
)
await browser.close()
console.log(JSON.stringify(result, null, 2))
process.exit(failed.length ? 1 : 0)
