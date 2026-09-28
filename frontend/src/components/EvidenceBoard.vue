<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { GitCompare, LoaderCircle, MousePointerClick, Trash2 } from 'lucide-vue-next'
import { caseApi } from '@/api'
import type { EvidenceBoardData, EvidenceLink } from '@/types'

const props = defineProps<{
  caseId: number
  /** 已发现线索数量。父组件调查出新线索后这个值会变，用它触发证据板重新拉取。 */
  clueCount: number
}>()

const board = ref<EvidenceBoardData>({ nodes: [], links: [], relationTypes: [] })
const loading = ref(false)
const saving = ref(false)
const fromClueId = ref<number | null>(null)
const toClueId = ref<number | null>(null)
const relationType = ref('')
const note = ref('')

/** 关系类型 → 颜色。关系类型本身来自后端白名单，这里只做可视化映射。 */
const relationColor: Record<string, string> = {
  SUPPORTS: 'var(--cyan)',
  CONTRADICTS: 'var(--red)',
  TIMELINE: 'var(--amber)',
  IDENTITY: 'var(--paper)',
}

function colorOf(relation: string) {
  return relationColor[relation] || 'var(--muted)'
}

const nodes = computed(() => board.value.nodes)
const links = computed(() => board.value.links)

/** 没有出现在任何一条连线上的线索。 */
const isolatedNodes = computed(() => {
  const linked = new Set<number>()
  links.value.forEach((link) => {
    linked.add(link.from.clueId)
    linked.add(link.to.clueId)
  })
  return nodes.value.filter((node) => !linked.has(node.clueId))
})

/**
 * 节点在画布上的位置：从正上方开始顺时针均匀排布。
 * 返回百分比坐标，HTML 节点与 SVG 连线共用同一套坐标（SVG 用 viewBox 0 0 100 100）。
 */
const positions = computed(() => {
  const map: Record<number, { x: number; y: number }> = {}
  const total = nodes.value.length
  nodes.value.forEach((node, index) => {
    const angle = (index / Math.max(total, 1)) * Math.PI * 2 - Math.PI / 2
    map[node.clueId] = {
      x: 50 + Math.cos(angle) * 35,
      y: 50 + Math.sin(angle) * 35,
    }
  })
  return map
})

/** 每条连线的 SVG 端点。两端线索不可见时跳过，避免画出悬空的线。 */
const lineSegments = computed(() =>
  links.value
    .map((link) => {
      const from = positions.value[link.from.clueId]
      const to = positions.value[link.to.clueId]
      if (!from || !to) return null
      return { link, from, to }
    })
    .filter((item): item is { link: EvidenceLink; from: { x: number; y: number }; to: { x: number; y: number } } => item !== null),
)

const canSubmit = computed(
  () => fromClueId.value !== null && toClueId.value !== null && fromClueId.value !== toClueId.value,
)

async function load() {
  loading.value = true
  try {
    const data = await caseApi.evidenceBoard(props.caseId)
    board.value = data
    relationType.value ||= data.relationTypes[0]?.value || ''
    // 线索集合变了以后，已经失效的选择要清掉，否则会出现「选了一个不存在的线索」。
    if (fromClueId.value && !data.nodes.some((node) => node.clueId === fromClueId.value)) {
      fromClueId.value = null
    }
    if (toClueId.value && !data.nodes.some((node) => node.clueId === toClueId.value)) {
      toClueId.value = null
    }
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

/**
 * 点击画布上的线索节点：先填起点，再填终点。
 * 两端都填满后再点节点会重新从起点开始，避免「点了没反应」。
 */
function pickNode(clueId: number) {
  if (fromClueId.value === clueId) {
    fromClueId.value = null
    return
  }
  if (toClueId.value === clueId) {
    toClueId.value = null
    return
  }
  if (fromClueId.value === null) {
    fromClueId.value = clueId
    return
  }
  if (toClueId.value === null) {
    toClueId.value = clueId
    return
  }
  fromClueId.value = clueId
  toClueId.value = null
}

async function submit() {
  if (!canSubmit.value || fromClueId.value === null || toClueId.value === null) return
  saving.value = true
  try {
    const created = await caseApi.createEvidenceLink(props.caseId, {
      fromClueId: fromClueId.value,
      toClueId: toClueId.value,
      relationType: relationType.value || undefined,
      note: note.value.trim() || undefined,
    })
    board.value.links.unshift(created)
    note.value = ''
    fromClueId.value = null
    toClueId.value = null
    ElMessage.success('关联已保存')
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    saving.value = false
  }
}

async function remove(link: EvidenceLink) {
  try {
    await ElMessageBox.confirm(
      `确定要删除「${link.from.clueCode} — ${link.relationLabel} — ${link.to.clueCode}」这条关联吗？`,
      '删除关联',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await caseApi.deleteEvidenceLink(props.caseId, link.id)
    board.value.links = board.value.links.filter((item) => item.id !== link.id)
    ElMessage.success('关联已删除')
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

onMounted(load)
watch(() => props.clueCount, load)
watch(() => props.caseId, load)
</script>

<template>
  <div class="board">
    <header class="board-head">
      <div>
        <span class="eyebrow">EVIDENCE BOARD</span>
        <h3>证据链</h3>
        <p>
          连线是你自己建立的推理关系，不是案件事实。后端只校验两端线索都属于本案且已被你发现，
          AI 可以评价你的关联是否自洽，但不会替你断定它成立。
        </p>
      </div>
      <div class="board-stats">
        <span><strong>{{ nodes.length }}</strong> 线索节点</span>
        <span><strong>{{ links.length }}</strong> 已建立关联</span>
        <span v-if="isolatedNodes.length"><strong>{{ isolatedNodes.length }}</strong> 尚未关联</span>
      </div>
    </header>

    <p v-if="loading" class="board-loading"><LoaderCircle :size="15" class="spin" /> 正在读取证据板...</p>

    <template v-else>
      <p v-if="!nodes.length" class="board-empty">
        <GitCompare :size="18" />
        <span>还没有任何线索可以关联。先去「地点」标签页调查，拿到线索后回到这里。</span>
      </p>

      <template v-else>
        <div class="board-canvas">
          <svg class="board-lines" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
            <line
              v-for="segment in lineSegments"
              :key="segment.link.id"
              :x1="segment.from.x"
              :y1="segment.from.y"
              :x2="segment.to.x"
              :y2="segment.to.y"
              :stroke="colorOf(segment.link.relationType)"
              stroke-width="1.5"
              stroke-dasharray="4 3"
              vector-effect="non-scaling-stroke"
            />
          </svg>
          <button
            v-for="node in nodes"
            :key="node.clueId"
            type="button"
            class="board-node"
            :class="{
              'is-from': fromClueId === node.clueId,
              'is-to': toClueId === node.clueId,
              'is-isolated': isolatedNodes.some((item) => item.clueId === node.clueId),
            }"
            :style="{
              left: `${positions[node.clueId]?.x ?? 50}%`,
              top: `${positions[node.clueId]?.y ?? 50}%`,
            }"
            :title="`${node.clueCode} ${node.title}`"
            @click="pickNode(node.clueId)"
          >
            <em>{{ node.clueCode }}</em>
            <strong>{{ node.title }}</strong>
          </button>
        </div>

        <form class="board-form" @submit.prevent="submit">
          <p class="board-hint">
            <MousePointerClick :size="14" />
            <span>点画布上的线索来选两端，也可以直接用下面的下拉框。</span>
          </p>
          <div class="board-form-row">
            <label>
              <span>起点线索</span>
              <select v-model.number="fromClueId">
                <option :value="null">请选择</option>
                <option v-for="node in nodes" :key="node.clueId" :value="node.clueId">
                  {{ node.clueCode }} {{ node.title }}
                </option>
              </select>
            </label>
            <label>
              <span>关系</span>
              <select v-model="relationType">
                <option v-for="option in board.relationTypes" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>
            </label>
            <label>
              <span>终点线索</span>
              <select v-model.number="toClueId">
                <option :value="null">请选择</option>
                <option v-for="node in nodes" :key="node.clueId" :value="node.clueId">
                  {{ node.clueCode }} {{ node.title }}
                </option>
              </select>
            </label>
          </div>
          <div class="board-form-row board-form-row--wide">
            <label class="board-note">
              <span>备注（可选，最多 300 字）</span>
              <input v-model="note" type="text" maxlength="300" placeholder="例如：两条线索的时间码互相冲突" />
            </label>
            <button class="board-submit" type="submit" :disabled="!canSubmit || saving">
              <LoaderCircle v-if="saving" :size="15" class="spin" />
              <GitCompare v-else :size="15" />
              {{ saving ? '保存中...' : '建立关联' }}
            </button>
          </div>
        </form>

        <ul v-if="links.length" class="board-links">
          <li v-for="link in links" :key="link.id">
            <span class="board-link-dot" :style="{ background: colorOf(link.relationType) }"></span>
            <div class="board-link-text">
              <strong>{{ link.from.clueCode }} {{ link.from.title }}</strong>
              <em :style="{ color: colorOf(link.relationType) }">{{ link.relationLabel }}</em>
              <strong>{{ link.to.clueCode }} {{ link.to.title }}</strong>
              <small v-if="link.note">{{ link.note }}</small>
            </div>
            <button type="button" class="board-link-delete" title="删除这条关联" @click="remove(link)">
              <Trash2 :size="14" />
            </button>
          </li>
        </ul>
        <p v-else class="board-no-link">还没有建立任何关联。选两条线索，说明它们之间的关系。</p>
      </template>
    </template>
  </div>
</template>

<style scoped>
.board {
  display: grid;
  gap: 16px;
}

.board-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.board-head h3 {
  margin: 6px 0 8px;
  font-size: 19px;
}

.board-head p {
  max-width: 62ch;
  margin: 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.75;
}

.board-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.018);
  border-radius: 6px;
}

.board-stats span {
  color: var(--faint);
  font-size: 10px;
  letter-spacing: 0.06em;
}

.board-stats strong {
  margin-right: 4px;
  color: var(--cyan);
  font-size: 15px;
}

.board-loading,
.board-empty,
.board-no-link {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  padding: 16px;
  border: 1px dashed var(--line-strong);
  color: var(--muted);
  font-size: 11px;
  line-height: 1.7;
  background: rgba(255, 255, 255, 0.014);
  border-radius: 6px;
}

.board-empty svg {
  flex: 0 0 auto;
  color: var(--faint);
}

.board-canvas {
  position: relative;
  min-height: 500px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background:
    linear-gradient(rgba(93, 183, 176, 0.07) 1px, transparent 1px),
    linear-gradient(90deg, rgba(93, 183, 176, 0.07) 1px, transparent 1px),
    radial-gradient(circle at 50% 50%, rgba(93, 183, 176, 0.09), transparent 46%),
    #0b1013;
  background-size: 34px 34px, 34px 34px, auto, auto;
  overflow: hidden;
}

.board-lines {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.board-node {
  position: absolute;
  z-index: 2;
  max-width: 116px;
  display: grid;
  gap: 2px;
  padding: 5px 7px;
  border: 1px solid var(--line);
  color: var(--muted);
  text-align: left;
  background: rgba(10, 14, 16, 0.94);
  border-radius: 4px;
  transform: translate(-50%, -50%);
  cursor: pointer;
  transition: border-color 0.24s ease, color 0.24s ease, box-shadow 0.24s ease;
}

.board-node em {
  color: var(--faint);
  font-size: 8px;
  font-style: normal;
  letter-spacing: 0.1em;
}

.board-node strong {
  font-size: 9px;
  font-weight: 500;
  line-height: 1.4;
}

.board-node.is-isolated {
  border-style: dashed;
}

.board-node.is-from,
.board-node.is-to {
  color: var(--text);
  border-color: rgba(93, 183, 176, 0.65);
  box-shadow: 0 0 0 4px rgba(93, 183, 176, 0.1);
}

.board-node.is-to {
  border-color: rgba(204, 155, 87, 0.7);
  box-shadow: 0 0 0 4px rgba(204, 155, 87, 0.12);
}

.board-node.is-from em {
  color: var(--cyan);
}

.board-node.is-to em {
  color: var(--amber);
}

.board-form {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.018);
  border-radius: 7px;
}

.board-hint {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  color: var(--faint);
  font-size: 10px;
}

.board-form-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.board-form-row--wide {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
}

.board-form label {
  display: grid;
  gap: 6px;
}

.board-form label > span {
  color: var(--faint);
  font-size: 9px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.board-form select,
.board-form input {
  width: 100%;
  min-height: 36px;
  padding: 7px 9px;
  border: 1px solid var(--line);
  color: var(--text);
  background: #0b1013;
  border-radius: 5px;
  font-size: 11px;
}

.board-form select:focus,
.board-form input:focus {
  outline: none;
  border-color: rgba(93, 183, 176, 0.55);
}

.board-submit {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 36px;
  padding: 0 18px;
  border: 1px solid rgba(93, 183, 176, 0.4);
  color: #bce9e4;
  background: var(--cyan-soft);
  border-radius: 5px;
  cursor: pointer;
  font-size: 11px;
}

.board-submit:disabled {
  opacity: 0.42;
  cursor: not-allowed;
}

.board-links {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.board-links li {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.014);
  border-radius: 5px;
}

.board-link-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  margin-top: 5px;
  border-radius: 50%;
}

.board-link-text {
  flex: 1 1 auto;
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.board-link-text strong {
  font-size: 11px;
  font-weight: 500;
}

.board-link-text em {
  font-size: 10px;
  font-style: normal;
}

.board-link-text small {
  flex: 1 0 100%;
  color: var(--faint);
  font-size: 10px;
  line-height: 1.6;
}

.board-link-delete {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 1px solid var(--line);
  color: var(--faint);
  background: transparent;
  border-radius: 4px;
  cursor: pointer;
  transition: color 0.2s ease, border-color 0.2s ease;
}

.board-link-delete:hover {
  color: var(--red);
  border-color: rgba(184, 93, 86, 0.5);
}

@media (max-width: 720px) {
  .board-form-row,
  .board-form-row--wide {
    grid-template-columns: minmax(0, 1fr);
  }

  .board-canvas {
    min-height: 520px;
  }
}
</style>
