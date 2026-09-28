<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  type?: string
}>()

const label = computed(() => {
  if (props.type === 'REAL') return '真实资料'
  if (props.type === 'ADAPTED') return '游戏改编'
  if (props.type === 'FICTIONAL') return '虚构内容'
  return props.type || '未知来源'
})

const className = computed(() => {
  if (props.type === 'REAL') return 'tag--real'
  if (props.type === 'ADAPTED') return 'tag--adapted'
  if (props.type === 'FICTIONAL') return 'tag--fictional'
  return ''
})
</script>

<template>
  <span class="tag" :class="className">{{ label }}</span>
</template>

