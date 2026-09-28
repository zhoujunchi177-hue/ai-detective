<script setup lang="ts">
import { ImageOff } from 'lucide-vue-next'
import { ref } from 'vue'

defineProps<{
  src?: string
  title: string
  code: string
}>()

const failed = ref(false)
</script>

<template>
  <div class="case-cover">
    <img v-if="src && !failed" :src="src" :alt="`${title}案件封面`" @error="failed = true" />
    <div v-else class="cover-fallback">
      <ImageOff :size="28" />
      <span>{{ code }}</span>
      <strong>{{ title }}</strong>
    </div>
    <div class="cover-scan" aria-hidden="true"></div>
  </div>
</template>

<style scoped>
.case-cover {
  position: relative;
  min-height: 230px;
  overflow: hidden;
  background: #0b1012;
}

.case-cover img {
  width: 100%;
  height: 100%;
  min-height: 230px;
  display: block;
  object-fit: cover;
  filter: saturate(0.72) contrast(1.05);
}

.case-cover::after {
  content: "";
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, transparent 36%, rgba(5, 7, 8, 0.88) 100%);
}

.cover-fallback {
  min-height: 230px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--faint);
  background:
    linear-gradient(135deg, rgba(93, 183, 176, 0.08), transparent 45%),
    repeating-linear-gradient(90deg, rgba(255, 255, 255, 0.025) 0 1px, transparent 1px 22px),
    #101619;
}

.cover-fallback strong {
  color: var(--muted);
  font-size: 13px;
}

.cover-scan {
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  background: repeating-linear-gradient(0deg, transparent 0 3px, rgba(255, 255, 255, 0.025) 3px 4px);
}
</style>

