<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ClipboardCheck } from 'lucide-vue-next'
import type { Clue, SubmitResult } from '@/types'

const props = defineProps<{
  modelValue: boolean
  clues: Clue[]
  loading: boolean
  result: SubmitResult | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: [payload: {
    hypothesis: string
    keyPeople: string
    keyTimeline: string
    evidenceClueIds: number[]
    reasoningText: string
    conclusion: string
  }]
}>()

const form = reactive({
  hypothesis: '',
  keyPeople: '',
  keyTimeline: '',
  evidenceClueIds: [] as number[],
  reasoningText: '',
  conclusion: '',
})

const report = computed(() => {
  if (!props.result?.aiReport) return null
  try {
    return JSON.parse(props.result.aiReport) as Record<string, unknown>
  } catch {
    return { summary: props.result.aiReport }
  }
})

watch(
  () => props.modelValue,
  (visible) => {
    if (visible && props.result) return
  },
)

function toggleEvidence(id: number) {
  if (form.evidenceClueIds.includes(id)) {
    form.evidenceClueIds = form.evidenceClueIds.filter((item) => item !== id)
  } else {
    form.evidenceClueIds.push(id)
  }
}

function submit() {
  emit('submit', { ...form, evidenceClueIds: [...form.evidenceClueIds] })
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    width="min(760px, 92vw)"
    class="submit-dialog"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="dialog-title">
        <ClipboardCheck :size="20" />
        <span>
          <strong>{{ result ? '结案报告' : '提交我的推理' }}</strong>
          <small>{{ result ? 'Java 已完成计分并保存记录' : '游戏评分由 Java 依据调查状态计算' }}</small>
        </span>
      </div>
    </template>

    <template v-if="!result">
      <div class="submit-form">
        <label>
          <span>核心假设</span>
          <el-input v-model="form.hypothesis" maxlength="1000" placeholder="用一句话写出你的核心假设" />
        </label>
        <div class="two-columns">
          <label>
            <span>关键人物</span>
            <el-input v-model="form.keyPeople" placeholder="按公开关系标注" />
          </label>
          <label>
            <span>关键时间线</span>
            <el-input v-model="form.keyTimeline" placeholder="列出关键时间点" />
          </label>
        </div>
        <label>
          <span>关键证据（已获得线索）</span>
          <div class="evidence-select">
            <button
              v-for="clue in clues"
              :key="clue.id"
              type="button"
              :class="{ active: form.evidenceClueIds.includes(clue.id) }"
              @click="toggleEvidence(clue.id)"
            >
              <span class="mono">{{ clue.clueCode }}</span>
              <strong>{{ clue.title }}</strong>
            </button>
            <p v-if="!clues.length">尚未获得线索，仍可提交，但得分会较低。</p>
          </div>
        </label>
        <label>
          <span>推理过程</span>
          <el-input v-model="form.reasoningText" type="textarea" :rows="5" maxlength="5000" show-word-limit />
        </label>
        <label>
          <span>最终结论</span>
          <el-input v-model="form.conclusion" type="textarea" :rows="3" maxlength="2000" />
        </label>
      </div>
      <div class="dialog-actions">
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <el-button
          type="primary"
          :loading="loading"
          :disabled="!form.hypothesis || !form.keyPeople || !form.keyTimeline || !form.reasoningText || !form.conclusion"
          @click="submit"
        >
          提交并生成调查报告
        </el-button>
      </div>
    </template>

    <template v-else>
      <div class="result-hero">
        <div class="score-ring">
          <strong>{{ result.totalScore }}</strong>
          <span>/ 100</span>
        </div>
        <div>
          <span class="eyebrow">CASE ASSESSMENT</span>
          <h3>{{ result.rating }}</h3>
          <p>EXP +{{ result.expReward }} · Coins +{{ result.coinReward }}</p>
        </div>
      </div>

      <div class="score-grid">
        <div><span>调查完成度</span><strong>{{ result.investigationScore }}/30</strong></div>
        <div><span>线索完整度</span><strong>{{ result.clueScore }}/25</strong></div>
        <div><span>时间线</span><strong>{{ result.timelineScore }}/20</strong></div>
        <div><span>逻辑推理</span><strong>{{ result.logicScore }}/25</strong></div>
      </div>

      <div class="report-box">
        <h4>调查报告</h4>
        <p>{{ report?.summary || '结案记录已保存。' }}</p>
        <template v-for="key in ['strengths', 'keyEvidence', 'missedClues', 'timelineIssues', 'logicGaps', 'nextSteps']" :key="key">
          <div v-if="Array.isArray(report?.[key]) && (report?.[key] as string[]).length" class="report-list">
            <strong>{{ key }}</strong>
            <span v-for="item in report?.[key] as string[]" :key="item">{{ item }}</span>
          </div>
        </template>
        <p class="reality-notice">{{ report?.realityNotice || '【现实案件资料】游戏推理不代表现实案件结论。' }}</p>
        <p v-if="result.aiNotice" class="ai-notice">{{ result.aiNotice }}</p>
      </div>

      <div v-if="result.newAchievements.length" class="achievement-list">
        <strong>解锁成就</strong>
        <span v-for="achievement in result.newAchievements" :key="achievement">{{ achievement }}</span>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-title {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--cyan);
}

.dialog-title strong,
.dialog-title small {
  display: block;
}

.dialog-title strong {
  color: var(--text);
  font-size: 16px;
}

.dialog-title small {
  margin-top: 3px;
  color: var(--faint);
  font-size: 10px;
}

.submit-form {
  display: grid;
  gap: 14px;
  max-height: 64vh;
  overflow-y: auto;
  padding-right: 5px;
}

.submit-form label > span {
  display: block;
  margin-bottom: 7px;
  color: var(--muted);
  font-size: 11px;
}

.two-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.evidence-select {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 7px;
  max-height: 180px;
  overflow-y: auto;
}

.evidence-select button {
  min-height: 53px;
  padding: 8px;
  border: 1px solid var(--line);
  color: var(--muted);
  text-align: left;
  background: rgba(255, 255, 255, 0.02);
  border-radius: 5px;
  cursor: pointer;
}

.evidence-select button.active {
  color: var(--text);
  border-color: rgba(93, 183, 176, 0.5);
  background: var(--cyan-soft);
}

.evidence-select button span,
.evidence-select button strong {
  display: block;
}

.evidence-select button span {
  color: var(--faint);
  font-size: 9px;
}

.evidence-select button strong {
  margin-top: 4px;
  font-size: 11px;
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}

.result-hero {
  display: flex;
  align-items: center;
  gap: 20px;
}

.score-ring {
  width: 112px;
  height: 112px;
  display: flex;
  align-items: baseline;
  justify-content: center;
  padding-top: 35px;
  border: 6px solid rgba(93, 183, 176, 0.18);
  border-top-color: var(--cyan);
  border-radius: 50%;
}

.score-ring strong {
  font-family: "Cascadia Mono", monospace;
  font-size: 34px;
}

.score-ring span {
  color: var(--faint);
  font-size: 10px;
}

.result-hero h3 {
  margin: 7px 0 0;
  font-size: 24px;
}

.result-hero p {
  margin: 8px 0 0;
  color: var(--amber);
}

.score-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin: 18px 0;
}

.score-grid div {
  padding: 10px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.02);
  border-radius: 5px;
}

.score-grid span,
.score-grid strong {
  display: block;
}

.score-grid span {
  color: var(--faint);
  font-size: 9px;
}

.score-grid strong {
  margin-top: 5px;
  color: var(--text);
  font-size: 14px;
}

.report-box {
  padding: 13px;
  border: 1px solid var(--line);
  background: rgba(7, 10, 12, 0.45);
  border-radius: 6px;
}

.report-box h4 {
  margin: 0 0 8px;
}

.report-box p {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.7;
}

.report-list {
  display: grid;
  gap: 3px;
  margin: 9px 0;
  padding-left: 10px;
  border-left: 2px solid var(--line-strong);
}

.report-list strong {
  color: var(--cyan);
  font-size: 10px;
}

.report-list span {
  color: var(--muted);
  font-size: 10px;
}

.reality-notice {
  padding: 8px;
  color: #e4bd7f !important;
  border: 1px solid rgba(204, 155, 87, 0.22);
  background: var(--amber-soft);
  border-radius: 4px;
}

.ai-notice {
  color: var(--red) !important;
}

.achievement-list {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
  margin-top: 13px;
}

.achievement-list strong {
  color: var(--faint);
  font-size: 10px;
}

.achievement-list span {
  padding: 5px 8px;
  color: #e4bd7f;
  border: 1px solid rgba(204, 155, 87, 0.28);
  background: var(--amber-soft);
  border-radius: 4px;
  font-size: 10px;
}

@media (max-width: 640px) {
  .two-columns,
  .evidence-select,
  .score-grid {
    grid-template-columns: 1fr;
  }
}
</style>
