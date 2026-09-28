<script setup lang="ts">
import { CalendarClock, ExternalLink, Fingerprint } from 'lucide-vue-next'
import dayjs from 'dayjs'
import MaterialTag from './MaterialTag.vue'
import type { Clue } from '@/types'

defineProps<{
  clue: Clue
  selectable?: boolean
  selected?: boolean
}>()

defineEmits<{
  toggle: [id: number]
}>()
</script>

<template>
  <article class="clue-card" :class="{ selected, selectable }" @click="selectable && $emit('toggle', clue.id)">
    <div class="clue-head">
      <span class="mono">{{ clue.clueCode }}</span>
      <MaterialTag :type="clue.sourceType" />
      <span v-if="clue.isHidden" class="hidden-mark"><Fingerprint :size="13" />隐藏</span>
    </div>
    <h4>{{ clue.title }}</h4>
    <p>{{ clue.content }}</p>
    <footer>
      <span>{{ clue.type }}</span>
      <span class="importance">
        <i v-for="index in clue.importance" :key="index"></i>
      </span>
      <span v-if="clue.createdAt"><CalendarClock :size="12" />{{ dayjs(clue.createdAt).format('MM-DD HH:mm') }}</span>
      <a
        v-if="clue.sourceUrl"
        :href="clue.sourceUrl"
        target="_blank"
        rel="noreferrer"
        @click.stop
      >
        {{ clue.sourceName || '来源' }} <ExternalLink :size="12" />
      </a>
    </footer>
  </article>
</template>

<style scoped>
.clue-card {
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.025), transparent 52%),
    rgba(15, 21, 24, 0.92);
  transition: border-color 0.2s ease, transform 0.2s ease, background 0.2s ease;
}

.clue-card:hover {
  transform: translateY(-1px);
  border-color: var(--line-strong);
}

.clue-card.selectable {
  cursor: pointer;
}

.clue-card.selected {
  border-color: rgba(93, 183, 176, 0.6);
  background: rgba(93, 183, 176, 0.08);
}

.clue-head,
footer {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.clue-head {
  color: var(--faint);
  font-size: 10px;
}

.hidden-mark {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--red);
}

h4 {
  margin: 11px 0 7px;
  font-size: 15px;
}

p {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.72;
}

footer {
  margin-top: 13px;
  color: var(--faint);
  font-size: 10px;
}

footer a,
footer span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

footer a {
  color: #8fd5cc;
}

.importance {
  display: inline-flex !important;
  gap: 2px !important;
}

.importance i {
  width: 8px;
  height: 3px;
  display: block;
  border-radius: 1px;
  background: var(--amber);
}
</style>

