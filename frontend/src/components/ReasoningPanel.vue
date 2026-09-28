<script setup lang="ts">
import { ref } from 'vue'
import { Activity, BrainCircuit, Lightbulb, Scale, TriangleAlert } from 'lucide-vue-next'
import type { ReasoningResult } from '@/types'

defineProps<{
  result: ReasoningResult | null
  loading: boolean
}>()

const emit = defineEmits<{
  analyze: [value: string]
  submit: []
}>()

const hypothesis = ref('')

function analyze() {
  if (!hypothesis.value.trim()) return
  emit('analyze', hypothesis.value.trim())
}
</script>

<template>
  <section class="reasoning panel">
    <div class="reasoning-head">
      <span class="reasoning-icon"><BrainCircuit :size="19" /></span>
      <div>
        <span class="eyebrow">HYPOTHESIS REVIEW</span>
        <h3>我的推理</h3>
      </div>
      <button class="submit-case" type="button" @click="emit('submit')">提交结案推理</button>
    </div>

    <div class="reasoning-grid">
      <div class="hypothesis-box">
        <label for="hypothesis">输入你的核心推论。AI 只评估它与当前已获得线索的一致性，不替你补全答案。</label>
        <textarea
          id="hypothesis"
          v-model="hypothesis"
          maxlength="5000"
          rows="6"
          placeholder="例如：我认为电梯视频的时间码并不适合作为精确时间依据，因为..."
        ></textarea>
        <button type="button" :disabled="loading || !hypothesis.trim()" @click="analyze">
          <Activity :size="15" />
          {{ loading ? '正在分析...' : '开始分析' }}
        </button>
      </div>

      <div class="analysis-output">
        <div v-if="!result" class="analysis-empty">
          <Scale :size="24" />
          <strong>等待推理输入</strong>
          <p>分析结果会拆分支持点、矛盾点、缺失证据和下一步调查方向。</p>
        </div>
        <template v-else>
          <div class="confidence">
            <div>
              <span>与当前游戏线索一致度</span>
              <strong>{{ result.confidence }}%</strong>
            </div>
            <div class="confidence-track"><i :style="{ width: `${result.confidence}%` }"></i></div>
            <small>{{ result.confidenceLabel }}</small>
          </div>
          <p class="summary">{{ result.summary }}</p>
          <div class="analysis-columns">
            <div>
              <h4><Lightbulb :size="14" />支持点</h4>
              <ul><li v-for="item in result.supportingEvidence" :key="item">{{ item }}</li></ul>
            </div>
            <div>
              <h4><TriangleAlert :size="14" />矛盾与漏洞</h4>
              <ul>
                <li v-for="item in [...result.contradictions, ...result.missingEvidence]" :key="item">{{ item }}</li>
                <li v-if="!result.contradictions.length && !result.missingEvidence.length">暂未识别出明确冲突</li>
              </ul>
            </div>
            <div>
              <h4><BrainCircuit :size="14" />继续调查</h4>
              <ul><li v-for="item in result.suggestions" :key="item">{{ item }}</li></ul>
            </div>
          </div>
          <p v-if="result.aiNotice" class="analysis-notice">{{ result.aiNotice }}</p>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.reasoning {
  padding: 18px;
}

.reasoning-head {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-bottom: 15px;
}

.reasoning-icon {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  color: var(--cyan);
  border: 1px solid rgba(93, 183, 176, 0.28);
  background: var(--cyan-soft);
  border-radius: 6px;
}

.reasoning-head h3 {
  margin: 3px 0 0;
  font-size: 16px;
}

.submit-case {
  min-height: 36px;
  margin-left: auto;
  padding: 0 13px;
  border: 1px solid rgba(204, 155, 87, 0.38);
  color: #e4bd7f;
  background: var(--amber-soft);
  border-radius: 5px;
  cursor: pointer;
}

.reasoning-grid {
  display: grid;
  grid-template-columns: minmax(300px, 0.78fr) minmax(460px, 1.22fr);
  gap: 18px;
}

.hypothesis-box label {
  display: block;
  margin-bottom: 8px;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.6;
}

.hypothesis-box textarea {
  width: 100%;
  min-height: 150px;
  resize: vertical;
  padding: 12px;
  border: 1px solid var(--line);
  color: var(--text);
  outline: 0;
  background: rgba(7, 10, 12, 0.7);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.7;
}

.hypothesis-box textarea:focus {
  border-color: rgba(93, 183, 176, 0.48);
}

.hypothesis-box > button {
  width: 100%;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 9px;
  border: 1px solid rgba(93, 183, 176, 0.38);
  color: #bce9e4;
  background: var(--cyan-soft);
  border-radius: 5px;
  cursor: pointer;
}

.hypothesis-box > button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.analysis-output {
  min-height: 222px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: rgba(7, 10, 12, 0.38);
}

.analysis-empty {
  height: 100%;
  min-height: 190px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--faint);
  text-align: center;
}

.analysis-empty strong {
  margin-top: 9px;
  color: var(--muted);
}

.analysis-empty p {
  max-width: 360px;
  font-size: 11px;
  line-height: 1.6;
}

.confidence {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 7px 12px;
}

.confidence span {
  color: var(--muted);
  font-size: 11px;
}

.confidence strong {
  color: var(--cyan);
  font-family: "Cascadia Mono", monospace;
  font-size: 18px;
}

.confidence-track {
  grid-column: 1 / -1;
  height: 5px;
  overflow: hidden;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.06);
}

.confidence-track i {
  height: 100%;
  display: block;
  background: linear-gradient(90deg, var(--red), var(--amber), var(--cyan));
}

.confidence small {
  grid-column: 1 / -1;
  color: var(--faint);
  font-size: 9px;
}

.summary {
  margin: 13px 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.7;
}

.analysis-columns {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.analysis-columns h4 {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0 0 6px;
  color: var(--text);
  font-size: 10px;
}

.analysis-columns ul {
  margin: 0;
  padding-left: 15px;
  color: var(--faint);
  font-size: 10px;
  line-height: 1.6;
}

.analysis-notice {
  margin: 12px 0 0;
  padding: 7px;
  color: #e4bd7f;
  border: 1px solid rgba(204, 155, 87, 0.22);
  background: var(--amber-soft);
  border-radius: 4px;
  font-size: 9px;
}

@media (max-width: 1080px) {
  .reasoning-grid {
    grid-template-columns: 1fr;
  }

  .analysis-columns {
    grid-template-columns: 1fr;
  }
}
</style>
