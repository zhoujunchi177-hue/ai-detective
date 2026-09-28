<script setup lang="ts">
import { computed, onMounted, ref, type Component } from 'vue'
import {
  Award,
  BadgeCheck,
  Clock4,
  Coins,
  EyeOff,
  FolderCheck,
  GitCompare,
  ImageDown,
  Lock,
  Medal,
  RefreshCw,
  Sparkles,
  type LucideIcon,
} from 'lucide-vue-next'
import dayjs from 'dayjs'
import { useAuthStore } from '@/stores/auth'
import ShareCardDialog from '@/components/ShareCardDialog.vue'
import {
  RARITY_LABEL,
  RARITY_ORDER,
  normalizeRarity,
  rarityClass,
  rarityLabel,
  rarityRank,
} from '@/utils/achievement-rarity'
import type { AchievementView } from '@/types'

const auth = useAuthStore()
const refreshing = ref(false)
const shareVisible = ref(false)
const activeFilter = ref<'all' | 'unlocked' | 'locked'>('all')

/**
 * achievements.icon 存的是 lucide 图标名（kebab-case）。
 * 这里用显式映射而不是动态 import —— 动态 import 会让打包器无法做 tree-shaking，
 * 会把整套图标库打进产物里。新增成就时在这里补一行即可。
 */
const iconMap: Record<string, LucideIcon> = {
  'folder-check': FolderCheck,
  'eye-off': EyeOff,
  'git-compare': GitCompare,
  'clock-4': Clock4,
}

const filters: { key: 'all' | 'unlocked' | 'locked'; label: string; icon: Component }[] = [
  { key: 'all', label: '全部徽章', icon: Medal },
  { key: 'unlocked', label: '已解锁', icon: BadgeCheck },
  { key: 'locked', label: '未解锁', icon: Lock },
]

/**
 * 按稀有度从高到低排，同档内按 id 稳定排列。
 * 收藏类界面把最难得的放前面，玩家一眼就能看到自己拿到的最稀有的那枚。
 */
const achievements = computed<AchievementView[]>(() =>
  [...(auth.profile?.achievements || [])].sort((left, right) => {
    const byRarity = rarityRank(left.rarity) - rarityRank(right.rarity)
    return byRarity !== 0 ? byRarity : left.id - right.id
  }),
)
const unlockedList = computed(() => achievements.value.filter((item) => item.unlocked))
const lockedList = computed(() => achievements.value.filter((item) => !item.unlocked))

/** 稀有度分布：每档共几枚、已解锁几枚。只展示实际存在的档位。 */
const raritySummary = computed(() =>
  RARITY_ORDER.map((rarity) => {
    const all = achievements.value.filter((item) => normalizeRarity(item.rarity) === rarity)
    return {
      rarity,
      label: RARITY_LABEL[rarity],
      total: all.length,
      unlocked: all.filter((item) => item.unlocked).length,
    }
  }).filter((entry) => entry.total > 0),
)

/** 分享图只画已解锁的徽章，没解锁的留在游戏里。 */
const shareBadges = computed(() =>
  unlockedList.value.map((item) => ({ name: item.name, rarity: item.rarity })),
)

const totalCount = computed(() => achievements.value.length)
const unlockedCount = computed(() => unlockedList.value.length)
const percent = computed(() =>
  totalCount.value ? Math.round((unlockedCount.value / totalCount.value) * 100) : 0,
)

/** 已解锁徽章累计发放的奖励，用来和用户当前 EXP / 调查币对照。 */
const earnedExp = computed(() =>
  unlockedList.value.reduce((sum, item) => sum + (item.rewardExp || 0), 0),
)
const earnedCoins = computed(() =>
  unlockedList.value.reduce((sum, item) => sum + (item.rewardCoins || 0), 0),
)

const visible = computed(() => {
  if (activeFilter.value === 'unlocked') return unlockedList.value
  if (activeFilter.value === 'locked') return lockedList.value
  return achievements.value
})

function countFor(key: 'all' | 'unlocked' | 'locked') {
  if (key === 'unlocked') return unlockedCount.value
  if (key === 'locked') return lockedList.value.length
  return totalCount.value
}

function iconFor(name: string): LucideIcon {
  return iconMap[name] || Award
}

function formatTime(value?: string) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : ''
}

async function reload() {
  refreshing.value = true
  try {
    await auth.refresh()
  } finally {
    refreshing.value = false
  }
}

onMounted(reload)
</script>

<template>
  <div class="page-wrap">
    <header class="page-heading">
      <div>
        <span class="eyebrow">ACHIEVEMENT VAULT</span>
        <h1 class="page-title">成就徽章</h1>
        <p class="page-subtitle">
          解锁条件与奖励由数据库 achievements 表定义，解锁状态由 Java 依据实际游戏进度判定，AI 不参与评分。
        </p>
      </div>
      <div class="heading-actions">
        <button class="vault-share" type="button" @click="shareVisible = true">
          <ImageDown :size="15" />
          生成分享图
        </button>
        <button class="vault-refresh" type="button" :disabled="refreshing" @click="reload">
          <RefreshCw :size="15" :class="{ spin: refreshing }" />
          刷新进度
        </button>
      </div>
    </header>

    <section class="vault-summary panel">
      <div class="vault-ring" :style="{ '--progress': `${percent}%` }">
        <div class="vault-ring-inner">
          <strong>{{ percent }}%</strong>
          <small>收集进度</small>
        </div>
      </div>

      <div class="vault-copy">
        <span class="eyebrow">COLLECTION PROGRESS</span>
        <h2>已解锁 {{ unlockedCount }} / {{ totalCount }} 枚徽章</h2>
        <div class="vault-track"><i :style="{ width: `${percent}%` }"></i></div>
        <p v-if="lockedList.length">
          还有 {{ lockedList.length }} 枚待解锁。徽章在结案或发现特定线索时自动发放，奖励直接计入 EXP 与调查币。
        </p>
        <p v-else-if="totalCount">全部徽章已收集完成。</p>
        <p v-else>暂无徽章数据。</p>
      </div>

      <div class="vault-stats">
        <div>
          <Sparkles :size="18" />
          <span>徽章累计 EXP</span>
          <strong>+{{ earnedExp }}</strong>
        </div>
        <div>
          <Coins :size="18" />
          <span>徽章累计调查币</span>
          <strong>+{{ earnedCoins }}</strong>
        </div>
        <div>
          <Lock :size="18" />
          <span>待解锁徽章</span>
          <strong>{{ lockedList.length }} 枚</strong>
        </div>
      </div>
    </section>

    <section v-if="raritySummary.length" class="rarity-board panel">
      <span class="eyebrow">RARITY BREAKDOWN</span>
      <p class="rarity-note">
        稀有度只表示拿到这枚徽章需要玩到多深，由数据库定义，不参与解锁判定 ——
        判定条件是 Java 代码里的硬逻辑。
      </p>
      <div class="rarity-row">
        <div
          v-for="entry in raritySummary"
          :key="entry.rarity"
          class="rarity-cell"
          :class="`rarity-cell--${entry.rarity.toLowerCase()}`"
        >
          <span class="rarity" :class="`rarity--${entry.rarity.toLowerCase()}`">
            {{ entry.label }}
          </span>
          <strong>{{ entry.unlocked }} / {{ entry.total }}</strong>
          <div class="rarity-track">
            <i :style="{ width: `${entry.total ? (entry.unlocked / entry.total) * 100 : 0}%` }"></i>
          </div>
        </div>
      </div>
    </section>

    <div class="vault-tabs">
      <button
        v-for="tab in filters"
        :key="tab.key"
        type="button"
        :class="{ active: activeFilter === tab.key }"
        :aria-pressed="activeFilter === tab.key"
        @click="activeFilter = tab.key"
      >
        <component :is="tab.icon" :size="15" />
        {{ tab.label }}
        <span class="tab-count">{{ countFor(tab.key) }}</span>
      </button>
    </div>

    <section v-if="visible.length" class="badge-grid">
      <article
        v-for="(item, index) in visible"
        :key="item.id"
        v-reveal="Math.min(index, 8) * 80"
        class="badge-card panel"
        :class="{ locked: !item.unlocked }"
      >
        <header class="badge-head">
          <span class="badge-medal">
            <component :is="iconFor(item.icon)" :size="24" />
          </span>
          <div>
            <h3>{{ item.name }}</h3>
            <div class="badge-tags">
              <span class="rarity" :class="rarityClass(item.rarity)">
                {{ rarityLabel(item.rarity) }}
              </span>
              <span class="badge-state" :class="item.unlocked ? 'is-unlocked' : 'is-locked'">
                {{ item.unlocked ? '已解锁' : '未解锁' }}
              </span>
            </div>
          </div>
        </header>

        <dl class="badge-meta">
          <dt>解锁条件</dt>
          <dd>{{ item.description }}</dd>
          <dt>解锁奖励</dt>
          <dd class="badge-reward">
            <span><Sparkles :size="12" />+{{ item.rewardExp || 0 }} EXP</span>
            <span><Coins :size="12" />+{{ item.rewardCoins || 0 }} 调查币</span>
          </dd>
        </dl>

        <footer class="badge-foot">
          <template v-if="item.unlocked">
            <BadgeCheck :size="14" />
            <span>解锁于 {{ formatTime(item.unlockedAt) || '时间未记录' }}</span>
          </template>
          <template v-else>
            <Lock :size="14" />
            <span>尚未解锁</span>
          </template>
        </footer>
      </article>
    </section>

    <section v-else class="vault-empty panel">
      <Medal :size="30" />
      <strong v-if="activeFilter === 'unlocked'">还没有已解锁的徽章</strong>
      <strong v-else-if="activeFilter === 'locked'">所有徽章都已解锁</strong>
      <strong v-else>暂无徽章数据</strong>
      <p v-if="activeFilter === 'unlocked'">完成并提交第一个案件，或发现隐藏线索后即可解锁。</p>
      <RouterLink v-if="activeFilter === 'unlocked'" to="/cases">前往案件档案</RouterLink>
    </section>

    <ShareCardDialog
      v-model="shareVisible"
      :nickname="auth.profile?.nickname || '调查员'"
      :user-id="auth.profile?.id || 0"
      :level="auth.profile?.level || 1"
      :badges="shareBadges"
      :total-badges="totalCount"
    />
  </div>
</template>

<style scoped>
.heading-actions {
  display: flex;
  align-items: center;
  gap: 9px;
  flex: 0 0 auto;
}

.vault-share {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 9px 14px;
  border: 1px solid rgba(93, 183, 176, 0.42);
  border-radius: 5px;
  color: #bce9e4;
  background: var(--cyan-soft);
  cursor: pointer;
  font-size: 12px;
}

.vault-share:hover {
  color: var(--text);
  border-color: rgba(93, 183, 176, 0.62);
}

.rarity-board {
  margin-top: 16px;
  padding: 18px 20px;
}

.rarity-note {
  margin: 8px 0 14px;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.65;
}

.rarity-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 11px;
}

.rarity-cell {
  padding: 11px 13px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.018);
  border-radius: 6px;
  /* currentColor 供下面的进度条复用，所以按档位给整格上色 */
  color: var(--rarity-common);
}

.rarity-cell--rare {
  color: var(--rarity-rare);
}

.rarity-cell--epic {
  color: var(--rarity-epic);
}

.rarity-cell--legendary {
  color: var(--rarity-legendary);
}

.rarity-cell strong {
  display: block;
  margin: 9px 0 8px;
  color: var(--text);
  font-family: "Cascadia Mono", monospace;
  font-size: 17px;
}

.rarity-track {
  height: 4px;
  overflow: hidden;
  background: rgba(166, 188, 196, 0.14);
  border-radius: 999px;
}

.rarity-track i {
  display: block;
  height: 100%;
  background: currentColor;
  border-radius: inherit;
  transition: width 0.4s ease;
}

.badge-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.vault-refresh {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  flex: 0 0 auto;
  padding: 9px 14px;
  border: 1px solid var(--line);
  border-radius: 5px;
  color: var(--muted);
  background: rgba(255, 255, 255, 0.02);
  cursor: pointer;
  font-size: 12px;
}

.vault-refresh:hover:not(:disabled) {
  color: var(--text);
  border-color: rgba(93, 183, 176, 0.42);
}

.vault-refresh:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.spin {
  animation: spin 1s linear infinite;
}

.vault-summary {
  display: grid;
  grid-template-columns: 132px minmax(0, 1fr) 220px;
  align-items: center;
  gap: 26px;
  padding: 22px;
}

.vault-ring {
  width: 118px;
  height: 118px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: conic-gradient(
    var(--cyan) var(--progress),
    rgba(166, 188, 196, 0.14) var(--progress)
  );
}

.vault-ring-inner {
  width: 92px;
  height: 92px;
  display: grid;
  place-content: center;
  text-align: center;
  background: #0d1215;
  border-radius: 50%;
}

.vault-ring-inner strong {
  font-size: 23px;
  color: var(--text);
}

.vault-ring-inner small {
  margin-top: 2px;
  color: var(--faint);
  font-size: 9px;
  letter-spacing: 0.08em;
}

.vault-copy h2 {
  margin: 6px 0 12px;
  font-size: 23px;
}

.vault-copy p {
  margin: 12px 0 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.65;
}

.vault-track {
  height: 6px;
  overflow: hidden;
  background: rgba(166, 188, 196, 0.14);
  border-radius: 999px;
}

.vault-track i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--cyan), var(--amber));
  border-radius: inherit;
  transition: width 0.4s ease;
}

.vault-stats {
  display: grid;
  gap: 9px;
}

.vault-stats > div {
  display: grid;
  grid-template-columns: 20px 1fr auto;
  align-items: center;
  gap: 9px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.018);
  border-radius: 6px;
}

.vault-stats svg {
  color: var(--cyan);
}

.vault-stats span {
  color: var(--muted);
  font-size: 10px;
}

.vault-stats strong {
  color: var(--text);
  font-size: 14px;
}

.vault-tabs {
  display: flex;
  gap: 5px;
  margin: 16px 0;
  padding: 4px;
  border: 1px solid var(--line);
  background: rgba(7, 10, 12, 0.48);
  border-radius: 6px;
  width: fit-content;
}

.vault-tabs button {
  min-height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 12px;
  border: 1px solid transparent;
  color: var(--muted);
  background: transparent;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}

.vault-tabs button.active {
  color: #bce9e4;
  border-color: rgba(93, 183, 176, 0.34);
  background: var(--cyan-soft);
}

.tab-count {
  padding: 1px 6px;
  border-radius: 999px;
  background: rgba(166, 188, 196, 0.14);
  font-size: 9px;
}

.badge-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 13px;
}

.badge-card {
  display: flex;
  flex-direction: column;
  gap: 13px;
  padding: 17px;
}

.badge-card.locked {
  opacity: 0.62;
}

.badge-head {
  display: flex;
  align-items: center;
  gap: 13px;
}

.badge-medal {
  width: 48px;
  height: 48px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  color: var(--amber);
  border: 1px solid rgba(204, 155, 87, 0.28);
  background: var(--amber-soft);
  border-radius: 7px;
}

.badge-card.locked .badge-medal {
  color: var(--faint);
  border-color: var(--line);
  background: rgba(255, 255, 255, 0.02);
}

.badge-head h3 {
  margin: 0 0 5px;
  font-size: 16px;
}

.badge-state {
  display: inline-block;
  padding: 2px 8px;
  border: 1px solid var(--line);
  border-radius: 999px;
  font-size: 9px;
  letter-spacing: 0.06em;
}

.badge-state.is-unlocked {
  color: var(--cyan);
  border-color: rgba(93, 183, 176, 0.42);
  background: var(--cyan-soft);
}

.badge-state.is-locked {
  color: var(--faint);
}

.badge-meta {
  margin: 0;
  display: grid;
  grid-template-columns: 66px minmax(0, 1fr);
  gap: 7px 10px;
  font-size: 11px;
}

.badge-meta dt {
  color: var(--faint);
  font-size: 10px;
}

.badge-meta dd {
  margin: 0;
  color: var(--muted);
  line-height: 1.6;
}

.badge-reward {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.badge-reward span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--amber);
}

.badge-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: auto;
  padding-top: 11px;
  border-top: 1px solid var(--line);
  color: var(--faint);
  font-size: 10px;
}

.badge-foot svg {
  color: var(--cyan);
}

.badge-card.locked .badge-foot svg {
  color: var(--faint);
}

.vault-empty {
  min-height: 280px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--faint);
  text-align: center;
}

.vault-empty strong {
  color: var(--muted);
}

.vault-empty p {
  margin: 0;
  font-size: 11px;
}

.vault-empty a {
  margin-top: 4px;
  color: #8fd5cc;
  font-size: 11px;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1020px) {
  .vault-summary {
    grid-template-columns: 118px minmax(0, 1fr);
  }

  .vault-stats {
    grid-column: 1 / -1;
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 720px) {
  .vault-summary {
    grid-template-columns: 1fr;
    justify-items: center;
    text-align: center;
    gap: 18px;
  }

  .vault-copy .eyebrow {
    display: block;
  }

  .vault-stats {
    width: 100%;
    grid-template-columns: 1fr;
  }

  .vault-tabs {
    width: 100%;
    overflow-x: auto;
  }

  .badge-grid {
    grid-template-columns: 1fr;
  }
}
</style>
