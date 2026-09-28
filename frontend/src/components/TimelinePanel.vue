<script setup lang="ts">
import { ExternalLink } from 'lucide-vue-next'
import { ref } from 'vue'
import MaterialTag from './MaterialTag.vue'
import type { TimelineItem } from '@/types'

defineProps<{
  items: TimelineItem[]
}>()

const activeId = ref<number | null>(null)
</script>

<template>
  <div class="timeline">
    <button
      v-for="(item, index) in items"
      :key="item.id"
      class="timeline-item"
      :class="{ active: activeId === item.id }"
      type="button"
      @click="activeId = activeId === item.id ? null : item.id"
    >
      <span class="timeline-node">{{ String(index + 1).padStart(2, '0') }}</span>
      <span class="timeline-body">
        <span class="timeline-date mono">{{ item.eventDateText }}</span>
        <strong>{{ item.title }}</strong>
        <span v-if="activeId === item.id" class="timeline-detail">
          {{ item.description }}
          <span class="timeline-meta">
            <MaterialTag :type="item.contentType" />
            <span v-if="item.people">{{ item.people }}</span>
            <span v-if="item.location">{{ item.location }}</span>
          </span>
          <a
            v-if="item.sourceUrl"
            :href="item.sourceUrl"
            target="_blank"
            rel="noreferrer"
            @click.stop
          >
            {{ item.sourceName }} <ExternalLink :size="13" />
          </a>
        </span>
      </span>
    </button>
  </div>
</template>

<style scoped>
.timeline {
  position: relative;
  padding-left: 9px;
}

.timeline::before {
  content: "";
  position: absolute;
  top: 8px;
  bottom: 8px;
  left: 19px;
  width: 1px;
  background: linear-gradient(var(--cyan), rgba(93, 183, 176, 0.08));
}

.timeline-item {
  position: relative;
  width: 100%;
  display: grid;
  grid-template-columns: 24px 1fr;
  gap: 14px;
  padding: 0 0 18px;
  border: 0;
  color: inherit;
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.timeline-node {
  z-index: 1;
  width: 21px;
  height: 21px;
  display: grid;
  place-items: center;
  border: 1px solid var(--line-strong);
  border-radius: 50%;
  color: var(--faint);
  background: var(--ink-1);
  font-family: "Cascadia Mono", monospace;
  font-size: 8px;
}

.timeline-body {
  display: block;
  padding: 2px 0 0;
}

.timeline-date {
  display: block;
  color: var(--cyan);
  font-size: 10px;
}

.timeline-body strong {
  display: block;
  margin-top: 4px;
  font-size: 14px;
}

.timeline-detail {
  display: block;
  margin-top: 8px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
  animation: reveal 0.25s ease;
}

.timeline-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 10px;
  color: var(--faint);
  font-size: 11px;
}

.timeline-detail a {
  width: fit-content;
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 10px;
  color: #8fd5cc;
  font-size: 11px;
}

.timeline-item.active .timeline-node {
  color: var(--cyan);
  border-color: var(--cyan);
  background: var(--cyan-soft);
}

@keyframes reveal {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>

