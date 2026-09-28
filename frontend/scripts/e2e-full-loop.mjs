/**
 * 29 项端到端验收：从一个全新账号开始，把「开局 → 调查 → 解锁 → 刷新/重登 → 证据板 → 日志 → 谜题 → NPC 对话 → 结案提交」整条链路跑一遍。
 *
 * 和另外两个脚本的分工：
 *   verify-api.ps1        —— 直接打接口，验证后端契约与拒绝路径
 *   visual-check.mjs      —— 多页面截图 + 各页面的视觉/交互不变量
 *   本脚本                 —— 真实浏览器里跑「一局游戏」，重点是**跨刷新/跨登录的持久化**
 *
 * 用法：先启动前端（5173）与后端（8080），然后
 *   cd frontend && node scripts/e2e-full-loop.mjs
 *
 * ⚠️ 5173 上跑的是 `dist/` 的**构建产物**（vite preview），不是 dev server。
 *    所以改完前端源码必须先重新构建，否则本脚本验的还是旧代码 —— 会得到「看起来通过」的假象，
 *    或者像 27 号检查那样报出一个你明明已经改过的问题。
 *      cd frontend
 *      mv dist ".runtime-old-dist-$(date +%s)"   # vite build 的 emptyOutDir 在沙箱里可能被拦
 *      node node_modules/vite/bin/vite.js build
 *      rm -rf .runtime-old-dist-*
 *
 * 退出码：全部通过（或仅有 SKIP）为 0；出现任何 FAIL 为 1。
 */
import fs from 'node:fs/promises'
import path from 'node:path'
import { chromium } from 'playwright-core'

const browserPath =
  process.env.BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const baseUrl = process.env.MINDTRACE_URL || 'http://127.0.0.1:5173'
const apiUrl = process.env.MINDTRACE_API || 'http://127.0.0.1:8080/api'
const outputDir = path.resolve('..', 'artifacts')
await fs.mkdir(outputDir, { recursive: true })

const checks = []
const notes = []

function check(name, condition, detail = '') {
  const passed = Boolean(condition)
  checks.push({ name, passed, detail })
  console.log(`${passed ? 'PASS' : 'FAIL'} ${name}${detail ? `  (${detail})` : ''}`)
  return passed
}

function skip(name, reason) {
  checks.push({ name, skipped: true, reason })
  console.log(`SKIP ${name}  (${reason})`)
}

// ---------------------------------------------------------------- 环境探测
// AI 是否真的接通，决定了「对话/推理」这类检查能不能算「已验证」。
// 没配 Key 时必须明说未验证，而不是让它默默算通过。
let aiConfigured = false
try {
  const health = await fetch(`${apiUrl}/health`).then((response) => response.json())
  aiConfigured = Boolean(health?.data?.deepSeekConfigured)
} catch (error) {
  console.log(`WARN 无法读取 /health：${error.message}`)
}
console.log(`INFO DeepSeek configured = ${aiConfigured}`)
notes.push(
  aiConfigured
    ? 'DeepSeek 已配置：对话/推理走真实 AI。'
    : 'DeepSeek 未配置：对话/推理走的是后端降级路径，**AI 实际调用本轮未验证**。',
)

const browser = await chromium.launch({ executablePath: browserPath, headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()

const consoleErrors = []
page.on('console', (message) => {
  if (message.type() === 'error') consoleErrors.push(message.text())
})
page.on('pageerror', (error) => consoleErrors.push(error.message))

const username = `e2e${Date.now()}`
const password = 'e2echeck12345'
notes.push(`本次使用的一次性账号：${username} / ${password}`)

/** 读取案件详情页上「进度 + 地点/线索/谜题」那一块的状态。 */
const readCaseState = () =>
  page.evaluate(() => {
    const bar = document.querySelector('.case-state-bar')
    const text = (bar?.textContent || '').replace(/\s+/g, ' ')
    const numbers = {}
    for (const label of ['地点', '线索', '谜题']) {
      const match = text.match(new RegExp(`${label} (\\d+)/(\\d+)`))
      numbers[label] = match ? { done: Number(match[1]), total: Number(match[2]) } : null
    }
    return {
      percent: Number((document.querySelector('.overall-progress strong')?.textContent || '').replace('%', '')),
      locations: numbers['地点'],
      clues: numbers['线索'],
      puzzles: numbers['谜题'],
      clueCards: document.querySelectorAll('.clue-grid .clue-card').length,
      availableNodes: document.querySelectorAll('.location-map .map-node--available').length,
      lockedNodes: document.querySelectorAll('.location-map .map-node--locked').length,
      investigatedNodes:
        document.querySelectorAll('.location-map .map-node--investigated, .location-map .map-node--completed')
          .length,
    }
  })

const openTab = async (label) => {
  await page.locator('nav.case-tabs button').filter({ hasText: label }).click()
  await page.waitForTimeout(500)
}

// ---------------------------------------------------------------- 1. 注册新账号
await page.goto(`${baseUrl}/register`, { waitUntil: 'networkidle' })
await page.getByPlaceholder('3-20 位字母数字').fill(username)
await page.getByPlaceholder('排行榜显示名称').fill('E2E 调查员')
const passwordInputs = page.locator('input[type="password"]')
await passwordInputs.nth(0).fill(password)
await passwordInputs.nth(1).fill(password)
await page.getByRole('button', { name: '创建档案并进入档案馆' }).click()
await page.waitForURL('**/home')
check('01 新账号注册后可进入档案馆', page.url().includes('/home'))

// ---------------------------------------------------------------- 2. 列表全是未开始
await page.goto(`${baseUrl}/cases`, { waitUntil: 'networkidle' })
await page.waitForTimeout(400)
const freshList = await page.evaluate(() =>
  Array.from(document.querySelectorAll('.case-grid .case-card')).map((card) => ({
    label: (card.querySelector('.progress-head span')?.textContent || '').trim(),
    percent: (card.querySelector('.progress-head strong')?.textContent || '').trim(),
    action: (card.querySelector('.enter-case')?.textContent || '').replace(/\s+/g, ' ').trim(),
  })),
)
check(
  '02 新账号的案件列表全部显示「未开始 / 0%」',
  freshList.length >= 3 && freshList.every((card) => card.label === '未开始' && card.percent === '0%'),
  `${freshList.length} 个案件`,
)

// ---------------------------------------------------------------- 3. 详情页开局状态
await page.goto(`${baseUrl}/case/1`, { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
await openTab('地点')
const start = await readCaseState()
await page.screenshot({ path: path.join(outputDir, 'e2e-01-start.png'), fullPage: true })

check(
  '03 开局进度为 0，且至少有 2 个可用节点、存在未解锁节点',
  start.percent === 0 && start.availableNodes >= 2 && start.lockedNodes >= 1,
  `进度 ${start.percent}% / 可用 ${start.availableNodes} / 锁定 ${start.lockedNodes}`,
)

// ---------------------------------------------------------------- 4. 未解锁节点说清原因
const lockedReason = await page.evaluate(() => {
  const locked = document.querySelector('.location-map .map-node--locked')
  return {
    ariaDisabled: locked?.getAttribute('aria-disabled') ?? null,
    hasBadge: Boolean(locked?.querySelector('.node-badge')),
  }
})
const activeBefore = await page.evaluate(
  () => (document.querySelector('.location-map .map-node.active')?.textContent || '').trim(),
)
await page.locator('.location-map .map-node--locked').first().click({ force: true })
await page.waitForTimeout(500)
const afterLockedClick = await page.evaluate(() => ({
  active: (document.querySelector('.location-map .map-node.active')?.textContent || '').trim(),
  warning: (document.querySelector('.el-message')?.textContent || '').trim(),
}))
check(
  '04 未解锁节点标 aria-disabled、点击不切换选中项、并说明解锁原因',
  lockedReason.ariaDisabled === 'true' &&
    afterLockedClick.active === activeBefore &&
    afterLockedClick.warning.length > 0,
  afterLockedClick.warning.slice(0, 40),
)

// ---------------------------------------------------------------- 5. 调查一个地点
const targetKey = await page.evaluate(() => {
  const node = document.querySelector('.location-map .map-node--available')
  return node?.textContent?.trim() ?? null
})
await page.locator('.investigation-button, .investigate-button').first().click()
await page.locator('.investigation-result').waitFor({ timeout: 30000 })
await page.waitForTimeout(400)
const narrative = await page.evaluate(
  () => (document.querySelector('.investigation-result')?.textContent || '').replace(/\s+/g, ' ').trim(),
)
check(
  '05 调查地点后返回叙述文本（不是空壳）',
  narrative.length >= 10,
  `${targetKey ?? '首个可用节点'} → ${narrative.slice(0, 36)}…`,
)

// ---------------------------------------------------------------- 6. 线索与进度增长
const afterInvestigate = await readCaseState()
check(
  '06 调查后发现线索，进度百分比大于 0',
  afterInvestigate.clues?.done > 0 && afterInvestigate.percent > 0,
  `线索 ${afterInvestigate.clues?.done}/${afterInvestigate.clues?.total}，进度 ${afterInvestigate.percent}%`,
)

// ---------------------------------------------------------------- 7. 节点状态推进
// 只断言「有节点从 AVAILABLE 推进到已调查」。
// 不能断言可用节点数一定减少 —— 调查大厅会同时解锁供水系统，
// 可用数可能持平（一个转走、一个解锁），那是设计如此。
check(
  '07 被调查的节点从 AVAILABLE 变成 INVESTIGATED/COMPLETED',
  afterInvestigate.investigatedNodes >= 1,
  `已调查 ${afterInvestigate.investigatedNodes}，可用 ${start.availableNodes} → ${afterInvestigate.availableNodes}`,
)

// ---------------------------------------------------------------- 8. 解锁了新节点
check(
  '08 调查后解锁了新的地点节点',
  afterInvestigate.investigatedNodes + afterInvestigate.availableNodes >
    start.investigatedNodes + start.availableNodes,
  `已开放 ${start.investigatedNodes + start.availableNodes} → ${afterInvestigate.investigatedNodes + afterInvestigate.availableNodes}`,
)

// ---------------------------------------------------------------- 9. 线索分级
// MaterialTag 渲染的是中文标签（真实资料 / 游戏改编 / 虚构内容），
// 分级靠的是 tag--real / tag--adapted / tag--fictional 这三个类名，所以按类名判定。
await openTab('线索')
const clueGrading = await page.evaluate(() => {
  const cards = Array.from(document.querySelectorAll('.clue-grid .clue-card'))
  const gradedCards = cards.filter(
    (card) => card.querySelector('.tag--real, .tag--adapted, .tag--fictional') !== null,
  )
  const labels = Array.from(
    new Set(
      gradedCards.flatMap((card) =>
        Array.from(card.querySelectorAll('.tag--real, .tag--adapted, .tag--fictional')).map((tag) =>
          (tag.textContent || '').trim(),
        ),
      ),
    ),
  )
  return { count: cards.length, graded: gradedCards.length, labels }
})
// 注意：这里**不能**顺带断言「分级类名只出现在线索卡里」。
// 标签页是 v-else-if 渲染的，此刻只有线索页在 DOM 里，那种断言恒为真、只会给人假信心。
// 真正能验到这条不变量的位置在第 26 项（结案后页头会出现状态徽章）。
check(
  '09 每条线索都标注 REAL / ADAPTED / FICTIONAL 分级',
  clueGrading.count > 0 &&
    clueGrading.graded === clueGrading.count &&
    clueGrading.labels.length > 0 &&
    clueGrading.labels.every((label) => ['真实资料', '游戏改编', '虚构内容'].includes(label)),
  `${clueGrading.graded}/${clueGrading.count} 条带分级标记（${clueGrading.labels.join('、')}）`,
)

// ---------------------------------------------------------------- 10. 刷新后恢复（核心）
const beforeReload = afterInvestigate
await page.reload({ waitUntil: 'networkidle' })
await page.waitForTimeout(900)
await openTab('地点')
const afterReload = await readCaseState()
check(
  '10 【持久化】刷新页面后进度、线索数、节点状态全部恢复',
  afterReload.percent === beforeReload.percent &&
    afterReload.clues?.done === beforeReload.clues?.done &&
    afterReload.investigatedNodes === beforeReload.investigatedNodes,
  `进度 ${afterReload.percent}%，线索 ${afterReload.clues?.done}，已调查 ${afterReload.investigatedNodes}`,
)

// ---------------------------------------------------------------- 11. 重登后恢复（核心）
await page.evaluate(() => {
  localStorage.removeItem('mindtrace_token')
  localStorage.removeItem('mindtrace_profile')
})
await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' })
await page.getByPlaceholder('输入用户名').fill(username)
await page.getByPlaceholder('输入密码').fill(password)
await page.getByRole('button', { name: '进入档案系统' }).click()
await page.waitForURL('**/home')
await page.goto(`${baseUrl}/case/1`, { waitUntil: 'networkidle' })
await page.waitForTimeout(900)
await openTab('地点')
const afterRelogin = await readCaseState()
check(
  '11 【持久化】退出登录后重新登录，进度仍然恢复（数据在 MySQL 而不是内存）',
  afterRelogin.percent === beforeReload.percent && afterRelogin.clues?.done === beforeReload.clues?.done,
  `进度 ${afterRelogin.percent}%，线索 ${afterRelogin.clues?.done}`,
)

// ---------------------------------------------------------------- 12. 列表与详情一致
await page.goto(`${baseUrl}/cases`, { waitUntil: 'networkidle' })
await page.waitForTimeout(500)
const listPercent = await page.evaluate(() => {
  // 按 href 精确定位 CASE-001 的卡片，而不是「碰巧排在第一个的那张」
  const card = document.querySelector('a.case-card[href$="/case/1"]')
  return Number((card?.querySelector('.progress-head strong')?.textContent || '').replace('%', ''))
})
check(
  '12 列表页百分比与详情页完全一致（综合完成度只在后端算一次）',
  listPercent === afterRelogin.percent,
  `列表 ${listPercent}% / 详情 ${afterRelogin.percent}%`,
)

// ---------------------------------------------------------------- 13-15. 证据板
await page.goto(`${baseUrl}/case/1`, { waitUntil: 'networkidle' })
await page.waitForTimeout(800)
await openTab('证据链')
await page.waitForTimeout(700)
const boardInitial = await page.evaluate(() => ({
  nodes: document.querySelectorAll('.board-canvas .board-node').length,
  links: document.querySelectorAll('.board-links > li').length,
  lines: document.querySelectorAll('.board-lines line').length,
}))
check(
  '13 证据板节点数 = 已发现线索数，且初始没有连线',
  boardInitial.nodes === afterRelogin.clues?.done && boardInitial.links === 0,
  `节点 ${boardInitial.nodes} / 线索 ${afterRelogin.clues?.done} / 连线 ${boardInitial.links}`,
)

// 建一条关联：用下拉框选两端（比点画布节点稳），填备注，提交。
// 三个 select 依次是：起点线索 / 关系类型 / 终点线索。
const boardSelects = page.locator('.board-form select')
await boardSelects.nth(0).selectOption({ index: 1 })
await boardSelects.nth(2).selectOption({ index: 2 })
await page.locator('.board-form input').fill('E2E 关联备注')
await page.waitForTimeout(250)
await page.locator('.board-submit').click()
await page.waitForTimeout(1200)
const boardAfterCreate = await page.evaluate(() => ({
  links: document.querySelectorAll('.board-links > li').length,
  lines: document.querySelectorAll('.board-lines line').length,
  noteShown: (document.querySelector('.board-links')?.textContent || '').includes('E2E 关联备注'),
}))
check(
  '14 建立证据关联后列表与 SVG 连线各 +1，且备注可见',
  boardAfterCreate.links === 1 && boardAfterCreate.lines === 1 && boardAfterCreate.noteShown,
  `连线 ${boardAfterCreate.links} / SVG ${boardAfterCreate.lines}`,
)

// 刷新后关联仍在
await page.reload({ waitUntil: 'networkidle' })
await page.waitForTimeout(900)
await openTab('证据链')
await page.waitForTimeout(700)
const boardAfterReload = await page.evaluate(() => ({
  links: document.querySelectorAll('.board-links > li').length,
  lines: document.querySelectorAll('.board-lines line').length,
}))
check(
  '15 【持久化】证据关联刷新后仍然存在',
  boardAfterReload.links === 1 && boardAfterReload.lines === 1,
  `连线 ${boardAfterReload.links}`,
)

// 删掉，保持账号干净
await page.locator('.board-links > li button').first().click()
await page.waitForTimeout(400)
// 确认框的按钮文案是「删除」（见 EvidenceBoard 的 ElMessageBox 配置），不是默认的「确定」
const confirmButton = page.locator('.el-message-box__btns button').filter({ hasText: '删除' })
if (await confirmButton.count()) {
  await confirmButton.first().click()
}
await page.waitForTimeout(1000)
const boardAfterDelete = await page.evaluate(
  () => document.querySelectorAll('.board-links > li').length,
)
check('16 删除关联后证据板回到原状', boardAfterDelete === 0, `连线 ${boardAfterDelete}`)

// ---------------------------------------------------------------- 17-18. 日志
await openTab('日志')
await page.waitForTimeout(700)

/**
 * 触发一次日志查询并等到响应回来。
 * 筛选已经移到服务端，关键词还有 300ms 防抖 —— 「填完等 400ms 再读 DOM」
 * 在慢机器上会读到**上一轮**结果，而读到的条数看起来完全合理，是静默假通过。
 */
async function triggerHistoryQuery(action) {
  const responded = page.waitForResponse(
    (res) => res.url().includes('/history') && res.request().method() === 'GET',
    { timeout: 20000 },
  )
  await action()
  await responded
  await page.waitForFunction(() => !document.querySelector('.log-loading'), null, { timeout: 10000 })
}

const logState = await page.evaluate(() => {
  const rows = Array.from(document.querySelectorAll('.log-list > li'))
  return {
    count: rows.length,
    leaked: rows.some((row) => (row.textContent || '').includes('"summary"')),
    hasInvestigation: Array.from(document.querySelectorAll('.log-chip')).some(
      (chip) => (chip.textContent || '').trim() === '调查地点',
    ),
  }
})
check(
  '17 日志记录了本次调查，且不泄露 AI 原始 JSON',
  logState.count > 0 && logState.hasInvestigation && !logState.leaked,
  `${logState.count} 条记录`,
)

await triggerHistoryQuery(() => page.locator('.log-search input').fill('绝对不存在的关键词zzz'))
const filteredOut = await page.evaluate(() => ({
  rows: document.querySelectorAll('.log-list > li').length,
  empty: Boolean(document.querySelector('.log-no-match')),
}))
await triggerHistoryQuery(() => page.locator('.log-search input').fill(''))
check(
  '18 日志关键词筛选真的改变结果（乱码关键词进入空状态）',
  filteredOut.rows === 0 && filteredOut.empty,
  `筛选后 ${filteredOut.rows} 条 + 空状态 ${filteredOut.empty}`,
)

// ---------------------------------------------------------------- 19. 谜题拖动
await openTab('谜题')
await page.waitForTimeout(500)
const puzzleCard = page.locator('.puzzle-card').first()
const sourceItems = puzzleCard.locator('.source-item')
const sourceCount = await sourceItems.count()
let puzzleDragOk = false
let puzzleDetail = '没有可用的排序谜题'
if (sourceCount >= 3) {
  for (let index = 0; index < 3; index += 1) await sourceItems.nth(index).click()
  await page.waitForTimeout(300)
  const rows = puzzleCard.locator('.selected-row')
  const rowTexts = () => puzzleCard.locator('.selected-row > span').allInnerTexts()
  const before = await rowTexts()
  if (before.length === 3) {
    await rows.nth(2).dragTo(rows.nth(0))
    await page.waitForTimeout(400)
    const after = await rowTexts()
    puzzleDragOk = JSON.stringify(after) === JSON.stringify([before[2], before[0], before[1]])
    puzzleDetail = `${before.join('|')} → ${after.join('|')}`
  }
}
check('19 谜题拖动排序真的改变了顺序（HTML5 拖放不是摆设）', puzzleDragOk, puzzleDetail)

// ---------------------------------------------------------------- 20. NPC 对话
await page.goto(`${baseUrl}/case/1`, { waitUntil: 'networkidle' })
await page.waitForTimeout(900)
const npcButtons = page.locator('.npc-selector button')
let chatOk = false
let chatDetail = '没有可对话的 NPC'
if ((await npcButtons.count()) > 0) {
  await npcButtons.first().click()
  await page.waitForTimeout(400)
  const question = '现场有哪些已经确认的事实？'
  await page.locator('.terminal-input textarea').fill(question)
  await page.locator('.terminal-input button').click()
  try {
    await page.waitForFunction(
      () => document.querySelectorAll('.terminal .message, .terminal .chat-message').length > 0,
      { timeout: 60000 },
    )
  } catch {
    // 消息容器类名可能不同，下面统一按文本判断
  }
  await page.waitForTimeout(1500)
  const sentCount = await page.evaluate(
    () => document.querySelectorAll('.terminal .message, .terminal .chat-message, .terminal .bubble').length,
  )
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  const persisted = await page.evaluate(
    (text) => (document.querySelector('.terminal')?.textContent || '').includes(text),
    question,
  )
  chatOk = sentCount > 0 && persisted
  chatDetail = `消息 ${sentCount} 条，刷新后仍在 = ${persisted}`
}
// 这条检查断言的是「消息落库 + 刷新仍在」这个持久化行为，与回复由谁生成无关。
// 但如果回复来自后端降级路径，就必须在详情里写明 —— AI 实际调用仍然未验证。
if (chatOk) {
  check(
    '20 【持久化】与 NPC 对话后消息落库，刷新后仍在',
    true,
    `${chatDetail}${aiConfigured ? '（AI 真实回复）' : '（降级回复，AI 实际调用未验证）'}`,
  )
} else if (aiConfigured) {
  check('20 【持久化】与 NPC 对话后消息落库，刷新后仍在', false, chatDetail)
} else {
  skip('20 【持久化】与 NPC 对话后消息落库，刷新后仍在', `DeepSeek 未配置且降级路径也没走通；${chatDetail}`)
}

// ---------------------------------------------------------------- 21-25. 结案提交
// 核心循环的最后一环，也是此前唯一没有覆盖的一段：
// 提交 → Java 计分 → 发经验/金币 → 评估成就 → 同步排行榜 → 案件变「已完成」。
await page.goto(`${baseUrl}/case/1`, { waitUntil: 'networkidle' })
await page.waitForTimeout(900)

// 不能用 hasText 定位这个弹窗：提交成功后标题会从「提交我的推理」变成「结案报告」，
// 定位器每次使用都会重新求值，标题一变就找不到元素了。
// 改成按结构定位 —— 弹窗里要么有表单、要么有结果区，两者覆盖整个生命周期。
const submitDialog = page
  .locator('.el-dialog')
  .filter({ has: page.locator('.submit-form, .result-hero') })
await page.locator('.reasoning .submit-case').click()
await submitDialog.waitFor({ state: 'visible', timeout: 15000 })

// 文本要写够长，才能命中 GameService.logicScore 的文本长度档位；
// 否则这里只能验证「表单能提交」，验证不了「计分真的随内容变化」。
const hypothesis = '凶手熟悉水泵房的管线走向，利用夜间巡检的十几分钟空档伪造了第一现场。'
const keyPeople = '夜班保安陈某、设备工程师林某、值班调度周某'
const keyTimeline = '22:10 最后一次巡检；23:40 水泵房报警；00:25 现场封锁；01:05 采样送检'
const reasoningText =
  '把已获得的线索按时间铺开可以发现一个矛盾：值班记录上 22:10 的巡检是正常的，但水泵房的水压曲线在 22:40 就出现了异常波动，' +
  '说明真正进入泵房的时间早于报警时间。报警器被人为延后触发，只有熟悉设备的人才能做到。' +
  '进一步看人员动线，夜班保安的巡检路线不经过泵房，而设备工程师当晚有临时工单记录，两者在时间上无法互相作证。' +
  '因此本案的关键不是「谁发现了现场」，而是「谁有能力和动机让现场晚被发现」。'
const conclusion =
  '综合以上线索，判断作案时间为 22:10 巡检之后的空档，作案人熟悉水泵房管线布局并具备延后报警的能力；现场存在被人为整理过的痕迹，第一现场并非报警位置。'

await submitDialog.getByPlaceholder('用一句话写出你的核心假设').fill(hypothesis)
await submitDialog.getByPlaceholder('按公开关系标注').fill(keyPeople)
await submitDialog.getByPlaceholder('列出关键时间点').fill(keyTimeline)

// 勾选前两条已获得线索作为「关键证据」，让 logicScore 的证据项真的拿分。
const evidenceButtons = submitDialog.locator('.evidence-select button')
const evidenceTotal = await evidenceButtons.count()
let selectedEvidence = 0
for (let index = 0; index < Math.min(2, evidenceTotal); index += 1) {
  await evidenceButtons.nth(index).click()
  selectedEvidence += 1
}

// 两个 textarea 依次是「推理过程」「最终结论」（都没有 placeholder，只能按顺序取）
const submitTextareas = submitDialog.locator('.submit-form textarea')
await submitTextareas.nth(0).fill(reasoningText)
await submitTextareas.nth(1).fill(conclusion)
await page.waitForTimeout(300)

const submitButton = submitDialog.getByRole('button', { name: '提交并生成调查报告' })
const submitEnabled = await submitButton.isEnabled()
await submitButton.click()

// 未配置 AI 时后端走降级路径，但同样会返回报告，所以等的是结果区而不是网络响应。
let reportRendered = true
try {
  await submitDialog.locator('.score-ring strong').waitFor({ timeout: 60000 })
} catch {
  reportRendered = false
}
await page.waitForTimeout(400)

const reportState = await submitDialog.evaluate((root) => {
  const text = (selector) => (root.querySelector(selector)?.textContent || '').replace(/\s+/g, ' ').trim()
  return {
    score: Number(text('.score-ring strong')),
    rating: text('.result-hero h3'),
    reward: text('.result-hero p'),
    scores: Array.from(root.querySelectorAll('.score-grid strong')).map((item) =>
      (item.textContent || '').trim(),
    ),
    summary: text('.report-box > p'),
    realityNotice: text('.reality-notice'),
    aiNotice: text('.ai-notice'),
    achievements: Array.from(root.querySelectorAll('.achievement-list span')).map((item) =>
      (item.textContent || '').trim(),
    ),
  }
})

check(
  '21 提交结案后渲染结案报告（分数 / 评级 / 四维得分 / 现实声明都在）',
  reportRendered &&
    submitEnabled &&
    Number.isFinite(reportState.score) &&
    reportState.score >= 0 &&
    reportState.score <= 100 &&
    reportState.rating.length > 0 &&
    reportState.scores.length === 4 &&
    reportState.realityNotice.length > 0,
  `得分 ${reportState.score}/100 · ${reportState.rating} · 证据 ${selectedEvidence}/${evidenceTotal} · 声明「${reportState.realityNotice.slice(0, 18)}…」`,
)

// 四个分项相加必须等于总分 —— 证明总分是后端按分项算出来的，不是另填的一个数。
const parsedScores = reportState.scores.map((item) => Number(String(item).split('/')[0]))
const scoreSum = parsedScores.reduce((sum, value) => sum + value, 0)
check(
  '22 四个分项得分相加 = 显示的总分（计分自洽，不是各写各的）',
  parsedScores.length === 4 &&
    parsedScores.every((value) => Number.isFinite(value)) &&
    scoreSum === reportState.score,
  `${reportState.scores.join(' + ')} = ${scoreSum}，总分 ${reportState.score}`,
)

// 首次结案必须发奖励，且奖励公式来自 GameService：
//   exp = score * 2 (+60 若 >= 80)，coins = score (+30 若 >= 80)
const rewardMatch = reportState.reward.match(/EXP \+(\d+) · Coins \+(\d+)/)
const expectedExp = reportState.score * 2 + (reportState.score >= 80 ? 60 : 0)
const expectedCoins = reportState.score + (reportState.score >= 80 ? 30 : 0)
check(
  '23 首次结案按总分发放 EXP 与金币（奖励公式与后端一致）',
  Boolean(rewardMatch) &&
    Number(rewardMatch[1]) === expectedExp &&
    Number(rewardMatch[2]) === expectedCoins &&
    Number(rewardMatch[2]) > 0,
  `${reportState.reward}（期望 EXP +${expectedExp} · Coins +${expectedCoins}）`,
)

// 成就条件必须两两不同。此前 TIMELINE_MASTER 的条件被写成和 FIRST_CASE 完全一样
// （都是 completedCases >= 1），结果第一次结案就同时解锁两枚，「时间线复核者」名不副实。
// 本局只调查了 1 个地点、也没有提交任何谜题，所以只该解锁「第一次调查」。
check(
  '24 未解谜题时只解锁「第一次调查」，不再连带给「时间线复核者」',
  reportState.achievements.includes('第一次调查') &&
    !reportState.achievements.includes('时间线复核者'),
  `本次解锁：${reportState.achievements.join('、') || '（无）'}`,
)

// logicScore 的档位来自 GameService.logicScore：
//   文本长度 >=180 → +8；证据数 min(8, n)；时间线 >=10 字 → +5；结论 >=25 字 → +4
// 上面的输入刻意写满所有档位，所以这里可以精确断言，而不是只断言「> 0」。
const logicScore = Number(String(reportState.scores[3] ?? '').split('/')[0])
const expectedLogic = Math.min(25, 17 + Math.min(8, selectedEvidence))
check(
  '25 逻辑推理分真的随输入内容变化（写满档位就拿到对应分数）',
  logicScore === expectedLogic,
  `逻辑 ${logicScore}/25（期望 ${expectedLogic}，证据 ${selectedEvidence} 条、文本 ≥180 字、时间线 ≥10 字、结论 ≥25 字）`,
)

// 第 26 项有 if/else 两个分支，两边必须用**同一个编号** ——
// 否则按编号核对的自检会发现「26 声明了却没执行」，而 25 出现了两次。
// AI 已配置时不能出现「未配置 Key」的降级提示；未配置时必须如实标注，不把降级包装成 AI 生成。
if (aiConfigured) {
  check(
    '26 已配置 AI：报告未出现「未配置 Key」的降级提示',
    reportState.aiNotice.length === 0,
    reportState.aiNotice || '（无降级提示）',
  )
} else {
  check(
    '26 【诚实性】未配置 AI 时报告明确标注降级，不冒充 AI 生成',
    reportState.aiNotice.includes('DEEPSEEK_API_KEY'),
    reportState.aiNotice || '（缺失！这会让人误以为报告是 AI 生成的）',
  )
}

// ---------------------------------------------------------------- 27. 结案后的持久化
// 先把结案报告本身留一张图：这是本轮新覆盖的那一段，值得有可视证据。
await submitDialog.screenshot({ path: path.join(outputDir, 'e2e-03-submit-report.png') })

await submitDialog.locator('.el-dialog__headerbtn').click()
await page.waitForTimeout(500)
await page.reload({ waitUntil: 'networkidle' })
await page.waitForTimeout(1000)
const closedOnDetail = await page.evaluate(() => {
  const header = document.querySelector('.case-header')
  const gradingClasses = ['tag--real', 'tag--adapted', 'tag--fictional']
  const gradingLabels = ['真实资料', '游戏改编', '虚构内容']
  const badges = Array.from(header?.querySelectorAll('.tag') || [])
  const statusBadge = badges.find((badge) => (badge.textContent || '').includes('已结案'))
  const gradingBadges = badges.filter((badge) =>
    gradingClasses.some((name) => badge.classList.contains(name)),
  )
  return {
    closedTag: (header?.textContent || '').includes('已结案'),
    statusBadgeText: (statusBadge?.textContent || '').trim(),
    statusBadgeClasses: statusBadge ? Array.from(statusBadge.classList) : [],
    // 页头里带分级类名的徽章，必须**全部**是真的内容分级。
    // 注意不能简单断言「页头没有分级类名」—— 案件自己的 MaterialTag 就在页头，
    // 那是正当用法；要抓的是「状态徽章借用了分级类名」。
    gradingBadgeLabels: gradingBadges.map((badge) => (badge.textContent || '').trim()),
    gradingLabels,
    headerState: (document.querySelector('.case-state-bar')?.textContent || '').replace(/\s+/g, ' ').trim(),
  }
})

await page.goto(`${baseUrl}/cases`, { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
const completedCard = await page.evaluate(() => {
  const card = document.querySelector('a.case-card[href$="/case/1"]')
  return {
    label: (card?.querySelector('.progress-head span')?.textContent || '').trim(),
    percent: Number((card?.querySelector('.progress-head strong')?.textContent || '').replace('%', '')),
    action: (card?.querySelector('.enter-case')?.textContent || '').replace(/\s+/g, ' ').trim(),
  }
})
check(
  '27 【持久化】结案后案件标记「已结案」，列表入口变为「查看结论」',
  closedOnDetail.closedTag &&
    completedCard.label === '已完成' &&
    completedCard.action === '查看结论' &&
    completedCard.percent > 0,
  `详情「已结案」=${closedOnDetail.closedTag}，列表「${completedCard.label} / ${completedCard.action} / ${completedCard.percent}%」`,
)

// 「已结案」是一个**状态**徽章，不是内容分级。曾经它直接借用 .tag--real 来取青色，
// 于是「按类名判定分级」的逻辑会把状态徽章当成一条 REAL 资料。
// 这里断言两件事：状态徽章确实在且带的是状态类名；页头所有分级类名都对应真的分级文案。
check(
  '28 状态徽章不复用内容分级类名（REAL/ADAPTED/FICTIONAL 只属于资料）',
  closedOnDetail.statusBadgeText.includes('已结案') &&
    !closedOnDetail.statusBadgeClasses.some(
      (name) => name.startsWith('tag--') && name !== 'tag--ok',
    ) &&
    closedOnDetail.gradingBadgeLabels.every((label) =>
      closedOnDetail.gradingLabels.includes(label),
    ),
  `状态徽章「${closedOnDetail.statusBadgeText}」类名 [${closedOnDetail.statusBadgeClasses.join(' ')}]；页头分级徽章 [${closedOnDetail.gradingBadgeLabels.join('、')}]`,
)

await page.screenshot({ path: path.join(outputDir, 'e2e-02-final.png'), fullPage: true })

notes.push(
  `结案得分 ${reportState.score}/100（${reportState.rating}），分项 ${reportState.scores.join(' / ')}，` +
    `${reportState.reward}，成就 ${reportState.achievements.length} 个。`,
)

// ---------------------------------------------------------------- 汇总
// 这条检查必须排在汇总之前。放在后面会让它既不被计入 passed/failed，
// 也不会影响退出码 —— 控制台有错时脚本会打印 FAIL 却依然以 0 退出，等于静默通过。
check('29 全程无控制台错误', consoleErrors.length === 0, `${consoleErrors.length} 条`)

const failed = checks.filter((item) => item.passed === false)
const skipped = checks.filter((item) => item.skipped)
const passed = checks.filter((item) => item.passed === true)

// 自检：脚本里声明了编号的检查项，必须都真的跑过。
// 它抓的是「以后有人把某个 check 包进条件分支里」这种情况 —— 那时编号会从
// 实际执行记录里消失，覆盖出现空洞却没人察觉。按**编号**核对而不是按出现次数：
// 有 if/else 的检查项会声明两次同一个编号，只有其中一条会执行，按次数算会误报。
const source = await fs.readFile(new URL(import.meta.url), 'utf8')
const declaredNumbers = new Set(
  Array.from(source.matchAll(/check\(\s*'(\d\d) /g), (match) => match[1]),
)
const executedNumbers = new Set(checks.map((item) => item.name.slice(0, 2)))
const vanished = [...declaredNumbers].filter((number) => !executedNumbers.has(number))
if (vanished.length) {
  console.log(`SELF-CHECK 失败：第 ${vanished.join('、')} 项声明了却没有执行（断言凭空消失）。`)
}

await browser.close()

const report = {
  baseUrl,
  apiUrl,
  checkedAt: new Date().toISOString(),
  username,
  aiConfigured,
  // 明确区分「AI 真的被调用过」和「只是把降级回复存下来了」
  aiActuallyCalled: aiConfigured,
  // 结案提交这一环的真实观测值，方便回看「后端到底算了多少分」
  submission: {
    score: reportState.score,
    rating: reportState.rating,
    breakdown: reportState.scores,
    reward: reportState.reward,
    realityNotice: reportState.realityNotice,
    aiNotice: reportState.aiNotice,
    achievements: reportState.achievements,
    evidenceSelected: selectedEvidence,
    evidenceAvailable: evidenceTotal,
  },
  notes,
  passed: passed.length,
  failed: failed.map((item) => item.name),
  skipped: skipped.map((item) => `${item.name} — ${item.reason}`),
  selfCheck: { declared: [...declaredNumbers].sort(), vanished },
  consoleErrors,
  checks,
}
await fs.writeFile(path.join(outputDir, 'e2e-full-loop.json'), JSON.stringify(report, null, 2), 'utf8')

console.log('')
console.log(
  `SUMMARY passed=${passed.length} failed=${failed.length} skipped=${skipped.length} declared=${declaredNumbers.size}`,
)
console.log(notes.join('\n'))
if (skipped.length) {
  console.log('')
  console.log('⚠️  以下检查本轮**未验证**（不计入通过）：')
  for (const item of skipped) console.log(`   - ${item.name}：${item.reason}`)
}
if (!aiConfigured) {
  console.log('')
  console.log('⚠️  DEEPSEEK_API_KEY 未配置：本轮 AI 实际调用未验证。')
}
if (vanished.length) {
  console.log('')
  console.log(`❌ 自检未通过：第 ${vanished.join('、')} 项没有执行，覆盖并不完整。`)
}

// 退出码必须同时反映「检查项失败」和「自检未通过」，
// 否则控制台错误这类失败会被静默吞掉。
process.exit(failed.length || vanished.length ? 1 : 0)
