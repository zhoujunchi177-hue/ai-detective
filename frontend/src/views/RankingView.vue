<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { BarChart3, Crown, FolderCheck, Medal, Trophy } from 'lucide-vue-next'
import { rankingApi } from '@/api'
import type { RankingBoard } from '@/types'

const activeType = ref('score')
const board = ref<RankingBoard | null>(null)
const loading = ref(true)
const tabs = [
  { key: 'score', label: '总积分', icon: Trophy },
  { key: 'cases', label: '完成案件', icon: FolderCheck },
  { key: 'level', label: '调查等级', icon: Medal },
]

async function load() {
  loading.value = true
  try {
    board.value = await rankingApi.list(activeType.value)
  } finally {
    loading.value = false
  }
}

watch(activeType, load)
onMounted(load)
</script>

<template>
  <div class="page-wrap">
    <header class="page-heading">
      <div>
        <span class="eyebrow">GLOBAL INVESTIGATOR INDEX</span>
        <h1 class="page-title">调查排行榜</h1>
        <p class="page-subtitle">排行榜由 MySQL 中的用户累计成绩生成，不包含 AI 评分或主观偏好。</p>
      </div>
      <div class="ranking-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          :class="{ active: activeType === tab.key }"
          @click="activeType = tab.key"
        >
          <component :is="tab.icon" :size="15" />{{ tab.label }}
        </button>
      </div>
    </header>

    <section v-if="board" class="ranking-board panel">
      <header class="board-head">
        <div>
          <span class="eyebrow">RANKING BOARD</span>
          <h2>{{ board.title }}</h2>
        </div>
        <BarChart3 :size="23" />
      </header>

      <div class="ranking-table">
        <div class="ranking-row table-head">
          <span>排名</span>
          <span>调查员</span>
          <span>等级</span>
          <span>EXP</span>
          <span>完成案件</span>
          <span>总分</span>
        </div>
        <div
          v-for="(entry, index) in board.entries"
          :key="entry.userId"
          v-reveal="Math.min(index, 8) * 70"
          class="ranking-row"
          :class="{ current: entry.currentUser, top: entry.rank <= 3 }"
        >
          <span class="rank-cell">
            <Crown v-if="entry.rank === 1" :size="18" />
            <strong>{{ entry.rank }}</strong>
          </span>
          <span class="investigator-cell">
            <i>{{ entry.nickname.slice(0, 1) }}</i>
            <span><strong>{{ entry.nickname }}</strong><small>ID-{{ String(entry.userId).padStart(5, '0') }}</small></span>
          </span>
          <span class="mono">LV.{{ entry.level }}</span>
          <span class="mono">{{ entry.exp }}</span>
          <span class="mono">{{ entry.completedCases }}</span>
          <span class="score-cell mono">{{ entry.totalScore }}</span>
        </div>
      </div>
    </section>

    <section v-else class="ranking-loading panel">
      <p>{{ loading ? '正在读取排行榜...' : '暂无排行数据' }}</p>
    </section>
  </div>
</template>

<style scoped>
.ranking-tabs {
  display: flex;
  gap: 5px;
  padding: 4px;
  border: 1px solid var(--line);
  background: rgba(7, 10, 12, 0.48);
  border-radius: 6px;
}

.ranking-tabs button {
  min-height: 34px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 11px;
  border: 1px solid transparent;
  color: var(--muted);
  background: transparent;
  border-radius: 4px;
  cursor: pointer;
}

.ranking-tabs button.active {
  color: #bce9e4;
  border-color: rgba(93, 183, 176, 0.34);
  background: var(--cyan-soft);
}

.ranking-board {
  overflow: hidden;
}

.board-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px;
  border-bottom: 1px solid var(--line);
}

.board-head h2 {
  margin: 5px 0 0;
  font-size: 22px;
}

.board-head svg {
  color: var(--cyan);
}

.ranking-table {
  overflow-x: auto;
}

.ranking-row {
  min-width: 780px;
  min-height: 62px;
  display: grid;
  grid-template-columns: 86px 1.5fr 0.7fr 0.8fr 0.9fr 0.8fr;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  border-bottom: 1px solid var(--line);
  color: var(--muted);
  font-size: 12px;
}

.ranking-row:last-child {
  border-bottom: 0;
}

.ranking-row.current {
  background: var(--cyan-soft);
}

.ranking-row.top {
  background: linear-gradient(90deg, rgba(204, 155, 87, 0.08), transparent 36%);
}

.table-head {
  min-height: 42px;
  color: var(--faint);
  background: rgba(255, 255, 255, 0.02);
  font-size: 9px;
  text-transform: uppercase;
}

.rank-cell {
  display: flex;
  align-items: center;
  gap: 7px;
}

.rank-cell svg {
  color: var(--amber);
}

.investigator-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.investigator-cell i {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  color: #d9f4f1;
  background: linear-gradient(145deg, #315b59, #714940);
  border-radius: 5px;
  font-style: normal;
}

.investigator-cell strong,
.investigator-cell small {
  display: block;
}

.investigator-cell strong {
  color: var(--text);
  font-size: 12px;
}

.investigator-cell small {
  margin-top: 2px;
  color: var(--faint);
  font-size: 8px;
}

.score-cell {
  color: var(--amber);
  font-size: 16px;
}

.ranking-loading {
  min-height: 300px;
  display: grid;
  place-items: center;
  color: var(--faint);
}

@media (max-width: 780px) {
  .ranking-tabs {
    width: 100%;
  }

  .ranking-tabs button {
    flex: 1;
    justify-content: center;
  }
}
</style>

