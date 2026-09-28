import type { AchievementRarity } from '@/types'

/**
 * 稀有度的展示元数据。
 *
 * 稀有度是**内容数据**（存在 achievements.rarity），由数据库决定；
 * 这里只负责「怎么显示」。判定解锁条件的逻辑在 Java 侧（AchievementService），
 * 前端拿到什么就显示什么，不参与判定。
 */

/** 从高到低，用于排序与统计。 */
export const RARITY_ORDER: AchievementRarity[] = ['LEGENDARY', 'EPIC', 'RARE', 'COMMON']

export const RARITY_LABEL: Record<AchievementRarity, string> = {
  COMMON: '常见',
  RARE: '稀有',
  EPIC: '史诗',
  LEGENDARY: '传说',
}

const RARITY_CLASS: Record<AchievementRarity, string> = {
  COMMON: 'rarity--common',
  RARE: 'rarity--rare',
  EPIC: 'rarity--epic',
  LEGENDARY: 'rarity--legendary',
}

/**
 * 后端已经做过一次兜底（AchievementService.normalizeRarity），
 * 但前端不能依赖它 —— 老接口缓存、手工造的数据都可能带脏值，
 * 所以这里再兜一次，保证渲染永远拿到四个已知值之一。
 */
export function normalizeRarity(value?: string): AchievementRarity {
  const upper = (value || '').trim().toUpperCase()
  return upper in RARITY_LABEL ? (upper as AchievementRarity) : 'COMMON'
}

export function rarityLabel(value?: string): string {
  return RARITY_LABEL[normalizeRarity(value)]
}

export function rarityClass(value?: string): string {
  return RARITY_CLASS[normalizeRarity(value)]
}

/** 排序用：数字越小越稀有。 */
export function rarityRank(value?: string): number {
  return RARITY_ORDER.indexOf(normalizeRarity(value))
}
