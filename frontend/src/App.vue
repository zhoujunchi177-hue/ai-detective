<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from '@/components/AppHeader.vue'
import AudioDock from '@/components/AudioDock.vue'
import { useAuthStore } from '@/stores/auth'
import { useAudioStore } from '@/stores/audio'

const auth = useAuthStore()
const audio = useAudioStore()
const route = useRoute()
const isGuestPage = computed(() => Boolean(route.meta.guest))

onMounted(() => {
  void auth.refresh()
  if (!isGuestPage.value) {
    audio.setTrack('lobby')
  }
})
</script>

<template>
  <div class="app-shell" :class="{ 'app-shell--guest': isGuestPage }">
    <div class="global-noise" aria-hidden="true"></div>
    <AppHeader v-if="!isGuestPage" />
    <main class="app-main">
      <RouterView />
    </main>
    <AudioDock v-if="!isGuestPage" />
  </div>
</template>

