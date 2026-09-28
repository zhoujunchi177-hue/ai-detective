<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowUpRight, Filter, FolderSearch, Search, Users } from 'lucide-vue-next'
import { caseApi } from '@/api'
import CaseCover from '@/components/CaseCover.vue'
import MaterialTag from '@/components/MaterialTag.vue'
import type { CaseSummary } from '@/types'

const cases = ref<CaseSummary[]>([])
const loading = ref(true)
const keyword = ref('')
const difficulty = ref('全部')

const filtered = computed(() =>
  cases.value.filter((item) => {
    const matchKeyword =
      !keyword.value ||
      item.title.includes(keyword.value) ||
      item.realName.toLowerCase().includes(keyword.value.toLowerCase())
    const matchDifficulty = difficulty.value === '全部' || item.difficulty === difficulty.value
    return matchKeyword && matchDifficulty
  }),
)

onMounted(async () => {
  try {
    cases.value = await caseApi.list()
  } finally {
    loading.value = false
  }
})

/** 存档状态文案。三种情况互斥，由后端的 started / completed 决定。 */
function progressLabel(item: CaseSummary) {
  if (!item.playerProgress?.started) return '未开始'
  return item.playerProgress.completed ? '已完成' : '调查中'
}

/** 卡片底部按钮文案：开始调查 / 继续调查 / 查看结论。 */
function actionLabel(item: CaseSummary) {
  if (!item.playerProgress?.started) return '开始调查'
  return item.playerProgress.completed ? '查看结论' : '继续调查'
}
</script>

<template>
  <div class="page-wrap">
    <header class="page-heading">
      <div>
        <span class="eyebrow">CASE ARCHIVE / {{ cases.length.toString().padStart(2, '0') }}</span>
        <h1 class="page-title">案件档案</h1>
        <p class="page-subtitle">选择案件后进入调查台。真实资料和游戏改编内容会在每个节点明确标注。</p>
      </div>
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="搜索案件名称" :prefix-icon="Search" clearable />
        <el-select v-model="difficulty" style="width: 138px">
          <template #prefix><Filter :size="14" /></template>
          <el-option label="全部难度" value="全部" />
          <el-option label="中等" value="中等" />
          <el-option label="较高" value="较高" />
          <el-option label="困难" value="困难" />
        </el-select>
      </div>
    </header>

    <div v-if="loading" class="case-grid">
      <div v-for="index in 3" :key="index" class="case-skeleton panel"></div>
    </div>
    <div v-else-if="filtered.length" class="case-grid">
      <RouterLink
        v-for="(item, index) in filtered"
        :key="item.id"
        v-reveal="Math.min(index, 5) * 90"
        class="case-card panel"
        :to="`/case/${item.id}`"
      >
        <CaseCover :src="item.coverUrl" :title="item.title" :code="item.caseCode" />
        <div class="case-content">
          <div class="case-meta">
            <span class="mono">{{ item.caseCode }}</span>
            <MaterialTag type="REAL" />
            <span class="tag">{{ item.caseType }}</span>
          </div>
          <h2>{{ item.title }}</h2>
          <p class="real-name">{{ item.realName }}</p>
          <p class="case-summary">{{ item.summary }}</p>

          <div class="case-stats">
            <div><span>年代</span><strong>{{ item.era }}</strong></div>
            <div><span>地点</span><strong>{{ item.location }}</strong></div>
            <div><span>难度</span><strong>{{ item.difficulty }}</strong></div>
          </div>

          <div class="completion-row">
            <span>资料完整度</span>
            <div class="completion-track"><i :style="{ width: `${item.completion}%` }"></i></div>
            <strong>{{ item.completion }}%</strong>
          </div>

          <!-- 玩家自己的进度。与上面的「资料完整度」是两回事，所以单独一块、单独配色。 -->
          <div class="progress-block" :class="{ 'is-completed': item.playerProgress?.completed }">
            <div class="progress-head">
              <span>{{ progressLabel(item) }}</span>
              <strong v-if="item.playerProgress">{{ item.playerProgress.percent }}%</strong>
              <strong v-else>—</strong>
            </div>
            <div class="progress-track">
              <i :style="{ width: `${item.playerProgress?.percent ?? 0}%` }"></i>
            </div>
            <p v-if="!item.playerProgress" class="progress-detail">未登录，无法读取存档</p>
            <p v-else-if="!item.playerProgress.started" class="progress-detail">还没有开始调查</p>
            <p v-else class="progress-detail">
              <span>地点 {{ item.playerProgress.investigatedLocations }}/{{ item.playerProgress.totalLocations }}</span>
              <span>线索 {{ item.playerProgress.discoveredClues }}/{{ item.playerProgress.totalClues }}</span>
              <span>谜题 {{ item.playerProgress.solvedPuzzles }}/{{ item.playerProgress.totalPuzzles }}</span>
            </p>
          </div>

          <footer>
            <span><Users :size="13" />{{ item.players.toLocaleString() }} 名调查员</span>
            <span class="enter-case">{{ actionLabel(item) }} <ArrowUpRight :size="14" /></span>
          </footer>
        </div>
      </RouterLink>
    </div>
    <div v-else class="empty-state panel">
      <FolderSearch :size="34" />
      <h2>没有匹配的案件档案</h2>
      <p>尝试清空搜索词或切换难度。</p>
    </div>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  gap: 9px;
  min-width: 410px;
}

.case-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 17px;
}

.case-card {
  overflow: hidden;
  transition: transform 0.22s ease, border-color 0.22s ease;
}

.case-card:hover {
  transform: translateY(-4px);
  border-color: var(--line-strong);
}

.case-content {
  padding: 18px;
}

.case-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  color: var(--cyan);
  font-size: 9px;
}

.case-content h2 {
  margin: 14px 0 4px;
  font-size: 24px;
}

.real-name {
  margin: 0;
  color: var(--faint);
  font-size: 10px;
}

.case-summary {
  min-height: 104px;
  margin: 14px 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.75;
}

.case-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border: 1px solid var(--line);
  border-radius: 5px;
}

.case-stats div {
  padding: 9px;
  border-right: 1px solid var(--line);
}

.case-stats div:last-child {
  border-right: 0;
}

.case-stats span,
.case-stats strong {
  display: block;
}

.case-stats span {
  color: var(--faint);
  font-size: 8px;
}

.case-stats strong {
  margin-top: 4px;
  overflow: hidden;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.completion-row {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 8px;
  margin-top: 15px;
  color: var(--faint);
  font-size: 9px;
}

.completion-track {
  height: 4px;
  overflow: hidden;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.06);
}

.completion-track i {
  height: 100%;
  display: block;
  background: linear-gradient(90deg, var(--cyan), var(--amber));
}

.completion-row strong {
  color: var(--cyan);
  font-family: "Cascadia Mono", monospace;
}

/* 玩家存档进度：与「资料完整度」视觉上明确分开，避免两个百分比被误读成同一个东西 */
.progress-block {
  margin-top: 12px;
  padding: 10px 11px;
  border: 1px solid var(--line);
  border-left: 2px solid var(--amber);
  background: rgba(204, 155, 87, 0.06);
  border-radius: 4px;
}

.progress-block.is-completed {
  border-left-color: var(--cyan);
  background: var(--cyan-soft);
}

.progress-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 7px;
  color: var(--muted);
  font-size: 10px;
  letter-spacing: 0.04em;
}

.progress-head strong {
  color: var(--amber);
  font-family: "Cascadia Mono", monospace;
  font-size: 13px;
}

.progress-block.is-completed .progress-head strong {
  color: var(--cyan);
}

.progress-track {
  height: 4px;
  overflow: hidden;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.07);
}

.progress-track i {
  height: 100%;
  display: block;
  background: linear-gradient(90deg, var(--amber), var(--cyan));
  transition: width 0.5s ease;
}

.progress-detail {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 8px 0 0;
  color: var(--faint);
  font-size: 9px;
}

footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 17px;
  padding-top: 13px;
  border-top: 1px solid var(--line);
  color: var(--faint);
  font-size: 9px;
}

footer span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.enter-case {
  color: var(--cyan);
}

.case-skeleton {
  min-height: 620px;
  animation: pulse 1.4s infinite alternate;
}

.empty-state {
  min-height: 320px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--faint);
}

.empty-state h2 {
  margin: 14px 0 6px;
  color: var(--muted);
}

.empty-state p {
  font-size: 12px;
}

@keyframes pulse {
  from { opacity: 0.4; }
  to { opacity: 0.75; }
}

@media (max-width: 1120px) {
  .case-grid {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 700px) {
  .filter-bar {
    width: 100%;
    min-width: 0;
  }

  .case-grid {
    grid-template-columns: 1fr;
  }
}
</style>
