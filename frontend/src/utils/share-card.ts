import { RARITY_LABEL, normalizeRarity } from './achievement-rarity'
import type { AchievementRarity } from '@/types'

/**
 * 成就分享图：在浏览器本地用 Canvas 2D 画出来，再导出 PNG。
 *
 * 为什么不用服务端生成：这张图没有任何服务端依赖 —— 不调 AI、不上传数据，
 * 玩家点一下就在本机画完。少一个接口、少一次上传，也少一份隐私顾虑。
 *
 * 配色刻意**不复用应用主题的 CSS 变量**：分享图会被贴到聊天窗口、社交平台，
 * 那些背景可能是亮的。所以这里用一套自带深色底板 + 描边的独立配色，
 * 保证放在任何背景上都立得住，而不是假设读者也在深色主题里。
 */

const CARD_WIDTH = 1200
const CARD_HEIGHT = 720
const PADDING = 56

const COLORS = {
  background: '#0a0e11',
  border: '#22313a',
  title: '#e7eceb',
  muted: '#93a1a3',
  faint: '#647275',
  accent: '#6fd0c6',
  amber: '#e9b969',
  track: '#1b2429',
  chipBg: '#111920',
}

const RARITY_COLOR: Record<AchievementRarity, string> = {
  COMMON: '#8b979a',
  RARE: '#6fd0c6',
  EPIC: '#a99bec',
  LEGENDARY: '#e9b969',
}

const FONT_STACK = '"Microsoft YaHei UI", "PingFang SC", "Noto Sans SC", sans-serif'
const MONO_STACK = '"Cascadia Mono", "Consolas", monospace'

export interface ShareCardBadge {
  name: string
  rarity?: string
}

export interface ShareCardOptions {
  nickname: string
  userId: number
  level: number
  /** 只传已解锁的徽章。 */
  badges: ShareCardBadge[]
  totalBadges: number
  generatedAt?: Date
}

/** Canvas 的 roundRect 在旧内核上缺失，自己画一个，避免版本差异。 */
function roundedRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  width: number,
  height: number,
  radius: number,
) {
  const r = Math.min(radius, width / 2, height / 2)
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.lineTo(x + width - r, y)
  ctx.arcTo(x + width, y, x + width, y + r, r)
  ctx.lineTo(x + width, y + height - r)
  ctx.arcTo(x + width, y + height, x + width - r, y + height, r)
  ctx.lineTo(x + r, y + height)
  ctx.arcTo(x, y + height, x, y + height - r, r)
  ctx.lineTo(x, y + r)
  ctx.arcTo(x, y, x + r, y, r)
  ctx.closePath()
}

/** 超宽就截断加省略号 —— 画布不会自动换行，必须自己处理。 */
function fitText(ctx: CanvasRenderingContext2D, text: string, maxWidth: number): string {
  if (ctx.measureText(text).width <= maxWidth) return text
  let result = text
  while (result.length > 1 && ctx.measureText(`${result}…`).width > maxWidth) {
    result = result.slice(0, -1)
  }
  return `${result}…`
}

function formatDate(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 画一张分享图并返回 canvas。
 * 做成纯函数（同样的输入 → 同样的图），便于单独验证渲染结果。
 */
export function renderShareCard(options: ShareCardOptions): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = CARD_WIDTH
  canvas.height = CARD_HEIGHT
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    throw new Error('当前浏览器不支持 Canvas 2D，无法生成分享图。')
  }

  const unlockedCount = options.badges.length
  const totalBadges = Math.max(options.totalBadges, unlockedCount)
  const percent = totalBadges ? Math.round((unlockedCount / totalBadges) * 100) : 0
  const generatedAt = options.generatedAt ?? new Date()

  // ---- 底板 ----
  ctx.fillStyle = COLORS.background
  ctx.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT)
  roundedRect(ctx, 12, 12, CARD_WIDTH - 24, CARD_HEIGHT - 24, 18)
  ctx.strokeStyle = COLORS.border
  ctx.lineWidth = 2
  ctx.stroke()

  // ---- 页眉 ----
  ctx.textBaseline = 'alphabetic'
  ctx.fillStyle = COLORS.accent
  ctx.font = `700 15px ${MONO_STACK}`
  ctx.fillText('M I N D T R A C E', PADDING, 84)

  ctx.fillStyle = COLORS.title
  ctx.font = `700 32px ${FONT_STACK}`
  ctx.fillText('AI 推理档案 · 成就收藏', PADDING, 124)

  ctx.fillStyle = COLORS.faint
  ctx.font = `400 14px ${MONO_STACK}`
  ctx.textAlign = 'right'
  ctx.fillText(formatDate(generatedAt), CARD_WIDTH - PADDING, 84)
  ctx.textAlign = 'left'

  ctx.strokeStyle = COLORS.border
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.moveTo(PADDING, 152)
  ctx.lineTo(CARD_WIDTH - PADDING, 152)
  ctx.stroke()

  // ---- 主数字 + 玩家信息 ----
  ctx.fillStyle = COLORS.title
  ctx.font = `700 76px ${MONO_STACK}`
  const percentText = `${percent}%`
  ctx.fillText(percentText, PADDING, 252)
  const percentWidth = ctx.measureText(percentText).width

  ctx.fillStyle = COLORS.muted
  ctx.font = `400 15px ${FONT_STACK}`
  ctx.fillText('收集进度', PADDING, 282)

  const infoLeft = PADDING + percentWidth + 36
  ctx.fillStyle = COLORS.title
  ctx.font = `700 26px ${FONT_STACK}`
  ctx.fillText(fitText(ctx, options.nickname, 420), infoLeft, 210)

  ctx.fillStyle = COLORS.muted
  ctx.font = `400 15px ${FONT_STACK}`
  ctx.fillText(
    `Lv.${options.level} · ID-${String(options.userId).padStart(5, '0')}`,
    infoLeft,
    240,
  )
  ctx.fillText(`已解锁 ${unlockedCount} / ${totalBadges} 枚徽章`, infoLeft, 272)

  // ---- 进度条 ----
  const trackX = PADDING
  const trackY = 306
  const trackWidth = CARD_WIDTH - PADDING * 2
  roundedRect(ctx, trackX, trackY, trackWidth, 10, 5)
  ctx.fillStyle = COLORS.track
  ctx.fill()
  if (percent > 0) {
    roundedRect(ctx, trackX, trackY, Math.max(10, (trackWidth * percent) / 100), 10, 5)
    ctx.fillStyle = COLORS.accent
    ctx.fill()
  }

  // ---- 徽章格子（2 列 × 最多 3 行，超出用 +N 收尾）----
  const chipTop = 356
  const chipHeight = 92
  const chipGapX = 20
  const chipGapY = 16
  const chipWidth = (trackWidth - chipGapX) / 2
  const maxChips = 6
  const shown = options.badges.slice(0, maxChips)

  shown.forEach((badge, index) => {
    const column = index % 2
    const row = Math.floor(index / 2)
    const x = trackX + column * (chipWidth + chipGapX)
    const y = chipTop + row * (chipHeight + chipGapY)
    const rarity = normalizeRarity(badge.rarity)
    const color = RARITY_COLOR[rarity]

    roundedRect(ctx, x, y, chipWidth, chipHeight, 12)
    ctx.fillStyle = COLORS.chipBg
    ctx.fill()
    ctx.strokeStyle = color
    ctx.globalAlpha = 0.5
    ctx.lineWidth = 1.5
    ctx.stroke()
    ctx.globalAlpha = 1

    // 稀有度色点 —— 不用图标，避免把整套图标库塞进分享图逻辑
    ctx.beginPath()
    ctx.arc(x + 30, y + chipHeight / 2, 9, 0, Math.PI * 2)
    ctx.fillStyle = color
    ctx.fill()

    ctx.fillStyle = COLORS.title
    ctx.font = `700 20px ${FONT_STACK}`
    ctx.fillText(fitText(ctx, badge.name, chipWidth - 100), x + 54, y + 42)

    ctx.fillStyle = color
    ctx.font = `700 12px ${FONT_STACK}`
    ctx.fillText(RARITY_LABEL[rarity], x + 54, y + 66)

    ctx.fillStyle = COLORS.faint
    ctx.font = `400 12px ${MONO_STACK}`
    ctx.textAlign = 'right'
    ctx.fillText(`#${String(index + 1).padStart(2, '0')}`, x + chipWidth - 18, y + 42)
    ctx.textAlign = 'left'
  })

  if (options.badges.length > maxChips) {
    ctx.fillStyle = COLORS.muted
    ctx.font = `400 15px ${FONT_STACK}`
    ctx.fillText(`另有 ${options.badges.length - maxChips} 枚徽章未在图中展示`, trackX, chipTop + 3 * (chipHeight + chipGapY) - 6)
  }

  if (!options.badges.length) {
    ctx.fillStyle = COLORS.faint
    ctx.font = `400 17px ${FONT_STACK}`
    ctx.fillText('还没有已解锁的徽章 —— 完成并提交第一个案件即可开张。', trackX, chipTop + 52)
  }

  // ---- 页脚：现实声明必须跟着图走 ----
  // 这张图会被转发到游戏之外，声明留在应用里等于没有声明。
  const footerY = CARD_HEIGHT - PADDING
  roundedRect(ctx, PADDING, footerY - 52, trackWidth, 44, 8)
  ctx.fillStyle = 'rgba(233, 185, 105, 0.09)'
  ctx.fill()
  ctx.strokeStyle = 'rgba(233, 185, 105, 0.28)'
  ctx.lineWidth = 1
  ctx.stroke()

  ctx.fillStyle = COLORS.amber
  ctx.font = `400 13px ${FONT_STACK}`
  ctx.fillText(
    fitText(
      ctx,
      '游戏内容区分 REAL / ADAPTED / FICTIONAL；游戏推理不代表现实案件结论。',
      trackWidth - 32,
    ),
    PADDING + 16,
    footerY - 24,
  )

  ctx.fillStyle = COLORS.faint
  ctx.font = `400 11px ${FONT_STACK}`
  ctx.fillText('本图由 MindTrace 在本地生成，未上传任何数据。', PADDING, footerY + 12)

  return canvas
}

/** canvas → PNG Blob。 */
export function shareCardBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (blob) {
        resolve(blob)
      } else {
        reject(new Error('分享图导出失败：浏览器没有返回图片数据。'))
      }
    }, 'image/png')
  })
}

/** 触发下载。 */
export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  // 立刻 revoke 会让部分浏览器来不及取数据，延后释放。
  window.setTimeout(() => URL.revokeObjectURL(url), 10000)
}

export function shareCardFilename(nickname: string, date = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  const stamp = `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}`
  const safeName = (nickname || 'investigator').replace(/[\\/:*?"<>|\s]+/g, '-').slice(0, 24)
  return `mindtrace-achievements-${safeName}-${stamp}.png`
}
