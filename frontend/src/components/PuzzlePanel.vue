<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { Check, ChevronDown, ChevronUp, GripVertical, Puzzle as PuzzleIcon } from 'lucide-vue-next'
import type { Puzzle } from '@/types'

const props = defineProps<{
  puzzles: Puzzle[]
  solvedCount: number
}>()

const emit = defineEmits<{
  submit: [puzzle: Puzzle, answer: string]
}>()

interface PuzzleItem {
  id: string
  label: string
}

interface PuzzlePayload {
  items?: PuzzleItem[]
  choices?: PuzzleItem[]
}

const state = reactive<Record<number, { selected: string[]; choice: string; expanded: boolean }>>({})

const parsed = computed(() =>
  Object.fromEntries(
    props.puzzles.map((puzzle) => [puzzle.id, JSON.parse(puzzle.payload) as PuzzlePayload]),
  ),
)

watch(
  () => props.puzzles,
  (puzzles) => {
    puzzles.forEach((puzzle) => {
      if (!state[puzzle.id]) {
        state[puzzle.id] = {
          selected: [],
          choice: '',
          expanded: puzzle.id === puzzles[0]?.id,
        }
      }
    })
  },
  { immediate: true, deep: true },
)

function addItem(puzzleId: number, itemId: string) {
  const current = state[puzzleId]
  if (!current.selected.includes(itemId)) {
    current.selected.push(itemId)
  }
}

function removeItem(puzzleId: number, itemId: string) {
  const current = state[puzzleId]
  current.selected = current.selected.filter((id) => id !== itemId)
}

function move(puzzleId: number, index: number, direction: -1 | 1) {
  const selected = state[puzzleId].selected
  const target = index + direction
  if (target < 0 || target >= selected.length) return
  ;[selected[index], selected[target]] = [selected[target], selected[index]]
}

/**
 * 拖动排序状态。只记录「从哪一行拖起、当前悬停到哪一行」，
 * 实际重排发生在 drop 时，避免拖动过程中频繁改动数组导致列表跳动。
 */
const dragState = reactive<{ puzzleId: number | null; fromIndex: number; overIndex: number }>({
  puzzleId: null,
  fromIndex: -1,
  overIndex: -1,
})

function resetDrag() {
  dragState.puzzleId = null
  dragState.fromIndex = -1
  dragState.overIndex = -1
}

function onDragStart(puzzleId: number, index: number, event: DragEvent) {
  dragState.puzzleId = puzzleId
  dragState.fromIndex = index
  dragState.overIndex = index
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    // Firefox 只有在设置了数据之后才会真正开始拖动。
    event.dataTransfer.setData('text/plain', String(index))
  }
}

function onDragOver(puzzleId: number, index: number) {
  if (dragState.puzzleId !== puzzleId) return
  dragState.overIndex = index
}

function onDrop(puzzleId: number) {
  const from = dragState.fromIndex
  const to = dragState.overIndex
  const sameList = dragState.puzzleId === puzzleId
  resetDrag()
  if (!sameList || from < 0 || to < 0 || from === to) return
  const selected = state[puzzleId].selected
  const [moved] = selected.splice(from, 1)
  selected.splice(to, 0, moved)
}

function isDragging(puzzleId: number, index: number) {
  return dragState.puzzleId === puzzleId && dragState.fromIndex === index
}

function isDropTarget(puzzleId: number, index: number) {
  return (
    dragState.puzzleId === puzzleId && dragState.overIndex === index && dragState.fromIndex !== index
  )
}

function answerFor(puzzle: Puzzle) {
  const current = state[puzzle.id]
  return puzzle.type === 'PERSON_RELATION' ? current.choice : current.selected.join(',')
}
</script>

<template>
  <div class="puzzle-stack">
    <article v-for="puzzle in puzzles" :key="puzzle.id" class="puzzle-card">
      <button class="puzzle-head" type="button" @click="state[puzzle.id].expanded = !state[puzzle.id].expanded">
        <span class="puzzle-icon"><PuzzleIcon :size="17" /></span>
        <span>
          <strong>{{ puzzle.title }}</strong>
          <small>{{ puzzle.description }}</small>
        </span>
        <span class="puzzle-type tag">{{ puzzle.type }}</span>
        <ChevronUp v-if="state[puzzle.id].expanded" :size="17" />
        <ChevronDown v-else :size="17" />
      </button>

      <div v-if="state[puzzle.id].expanded" class="puzzle-body">
        <template v-if="puzzle.type === 'PERSON_RELATION'">
          <label
            v-for="choice in parsed[puzzle.id].choices"
            :key="choice.id"
            class="choice-row"
            :class="{ selected: state[puzzle.id].choice === choice.id }"
          >
            <input v-model="state[puzzle.id].choice" type="radio" :value="choice.id" />
            <span class="radio-dot"></span>
            <span>{{ choice.label }}</span>
          </label>
        </template>

        <template v-else>
          <div class="puzzle-columns">
            <div>
              <span class="column-label">档案片段</span>
              <button
                v-for="item in parsed[puzzle.id].items"
                :key="item.id"
                class="source-item"
                type="button"
                :disabled="state[puzzle.id].selected.includes(item.id)"
                @click="addItem(puzzle.id, item.id)"
              >
                {{ item.label }}
              </button>
            </div>
            <div>
              <span class="column-label">你的排序 / 选择</span>
              <div v-if="state[puzzle.id].selected.length" class="selected-list">
                <div
                  v-for="(itemId, index) in state[puzzle.id].selected"
                  :key="itemId"
                  class="selected-row"
                  :class="{
                    dragging: isDragging(puzzle.id, index),
                    'drop-target': isDropTarget(puzzle.id, index),
                  }"
                  draggable="true"
                  @dragstart="onDragStart(puzzle.id, index, $event)"
                  @dragover.prevent="onDragOver(puzzle.id, index)"
                  @drop.prevent="onDrop(puzzle.id)"
                  @dragend="resetDrag"
                >
                  <GripVertical :size="14" class="grip" />
                  <span>{{ parsed[puzzle.id].items?.find((item) => item.id === itemId)?.label }}</span>
                  <button type="button" title="上移" @click="move(puzzle.id, index, -1)">↑</button>
                  <button type="button" title="下移" @click="move(puzzle.id, index, 1)">↓</button>
                  <button type="button" title="移除" @click="removeItem(puzzle.id, itemId)">×</button>
                </div>
                <p class="drag-hint">拖动任意一行可调整顺序，也可以用 ↑ ↓ 微调。</p>
              </div>
              <p v-else class="empty-selection">从左侧选择证据。时间排序谜题需要保持正确顺序。</p>
            </div>
          </div>
        </template>

        <button class="submit-puzzle" type="button" :disabled="!answerFor(puzzle)" @click="emit('submit', puzzle, answerFor(puzzle))">
          <Check :size="15" />提交校验
        </button>
      </div>
    </article>
  </div>
</template>

<style scoped>
.puzzle-stack {
  display: grid;
  gap: 12px;
}

.puzzle-card {
  border: 1px solid var(--line);
  border-radius: 7px;
  background: rgba(13, 18, 21, 0.84);
  overflow: hidden;
}

.puzzle-head {
  width: 100%;
  display: grid;
  grid-template-columns: 34px 1fr auto auto;
  align-items: center;
  gap: 11px;
  padding: 14px;
  border: 0;
  color: inherit;
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.puzzle-icon {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  color: var(--amber);
  border: 1px solid rgba(204, 155, 87, 0.25);
  background: var(--amber-soft);
  border-radius: 5px;
}

.puzzle-head strong,
.puzzle-head small {
  display: block;
}

.puzzle-head strong {
  font-size: 14px;
}

.puzzle-head small {
  margin-top: 4px;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.5;
}

.puzzle-body {
  padding: 0 14px 14px;
  border-top: 1px solid var(--line);
}

.puzzle-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  padding-top: 14px;
}

.column-label {
  display: block;
  margin-bottom: 8px;
  color: var(--faint);
  font-size: 10px;
  text-transform: uppercase;
}

.source-item,
.choice-row {
  width: 100%;
  min-height: 40px;
  margin-bottom: 7px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  color: var(--muted);
  text-align: left;
  background: rgba(255, 255, 255, 0.02);
  border-radius: 5px;
}

.source-item {
  cursor: pointer;
}

.source-item:hover:not(:disabled) {
  color: var(--text);
  border-color: rgba(93, 183, 176, 0.45);
}

.source-item:disabled {
  opacity: 0.28;
}

.selected-list {
  display: grid;
  gap: 6px;
}

.selected-row {
  min-height: 38px;
  display: grid;
  grid-template-columns: 16px 1fr repeat(3, 25px);
  align-items: center;
  gap: 4px;
  padding: 5px 7px;
  border: 1px solid rgba(93, 183, 176, 0.24);
  border-radius: 5px;
  background: var(--cyan-soft);
  font-size: 11px;
}

.selected-row .grip {
  color: var(--faint);
  cursor: grab;
}

.selected-row:active .grip {
  cursor: grabbing;
}

.selected-row.dragging {
  opacity: 0.4;
}

.selected-row.drop-target {
  border-color: rgba(93, 183, 176, 0.85);
  box-shadow: 0 0 0 1px rgba(93, 183, 176, 0.55);
}

.drag-hint {
  margin: 2px 0 0;
  color: var(--faint);
  font-size: 10px;
}

.selected-row button {
  width: 24px;
  height: 24px;
  border: 1px solid var(--line);
  color: var(--muted);
  background: transparent;
  border-radius: 3px;
  cursor: pointer;
}

.empty-selection {
  color: var(--faint);
  font-size: 11px;
}

.choice-row {
  display: flex;
  align-items: center;
  gap: 9px;
  cursor: pointer;
}

.choice-row input {
  display: none;
}

.radio-dot {
  width: 13px;
  height: 13px;
  border: 1px solid var(--line-strong);
  border-radius: 50%;
}

.choice-row.selected {
  color: var(--text);
  border-color: rgba(93, 183, 176, 0.48);
  background: var(--cyan-soft);
}

.choice-row.selected .radio-dot {
  border: 4px solid var(--cyan);
}

.submit-puzzle {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-top: 14px;
  padding: 9px 13px;
  border: 1px solid rgba(93, 183, 176, 0.35);
  color: #9ee0d9;
  background: var(--cyan-soft);
  border-radius: 5px;
  cursor: pointer;
}

.submit-puzzle:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

@media (max-width: 720px) {
  .puzzle-columns {
    grid-template-columns: 1fr;
  }
}
</style>

