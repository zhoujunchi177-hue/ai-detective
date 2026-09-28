<script setup lang="ts">
import { FileQuestion } from 'lucide-vue-next'
</script>

<template>
  <div class="not-found">
    <FileQuestion :size="44" />
    <span class="eyebrow">ARCHIVE ENTRY NOT FOUND</span>
    <h1>这份档案不存在</h1>
    <p>路径可能已变更，或你没有访问该记录的权限。</p>
    <RouterLink to="/home">返回档案馆</RouterLink>
  </div>
</template>

<style scoped>
.not-found {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--faint);
  text-align: center;
}

.not-found h1 {
  margin: 13px 0 6px;
  color: var(--text);
}

.not-found p {
  margin: 0 0 22px;
  font-size: 12px;
}

.not-found a {
  padding: 9px 13px;
  color: var(--cyan);
  border: 1px solid rgba(93, 183, 176, 0.3);
  background: var(--cyan-soft);
  border-radius: 5px;
  font-size: 12px;
}
</style>
