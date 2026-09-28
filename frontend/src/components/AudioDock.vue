<script setup lang="ts">
import { computed, ref } from 'vue'
import { Music2, SlidersHorizontal, Volume2, VolumeX, Waves, X } from 'lucide-vue-next'
import { useAudioStore } from '@/stores/audio'

const audio = useAudioStore()
const panelOpen = ref(false)

const bgmPercent = computed(() => Math.round(audio.bgmVolume * 100))
const sfxPercent = computed(() => Math.round(audio.sfxVolume * 100))
const allMuted = computed(() => audio.muted)
</script>

<template>
  <aside class="audio-dock">
    <button
      class="audio-icon audio-toggle--bgm"
      :class="{ active: audio.bgmEnabled }"
      type="button"
      title="背景音乐开关"
      :aria-pressed="audio.bgmEnabled"
      @click="audio.toggleBgm"
    >
      <Music2 v-if="audio.bgmEnabled" :size="16" />
      <VolumeX v-else :size="16" />
    </button>

    <div class="audio-status">
      <strong>{{ audio.statusText }}</strong>
      <span>{{ audio.statusHint }}</span>
    </div>

    <button
      class="audio-icon audio-settings-button"
      :class="{ active: panelOpen }"
      type="button"
      title="声音设置"
      aria-label="声音设置"
      :aria-expanded="panelOpen"
      @click="panelOpen = !panelOpen"
    >
      <SlidersHorizontal :size="16" />
    </button>

    <section v-if="panelOpen" class="audio-panel">
      <header class="audio-panel__head">
        <strong>声音设置</strong>
        <button
          class="audio-panel__close"
          type="button"
          title="收起"
          aria-label="收起声音设置"
          @click="panelOpen = false"
        >
          <X :size="14" />
        </button>
      </header>

      <div class="audio-panel__row audio-row--bgm">
        <button
          class="audio-icon audio-toggle--bgm-row"
          :class="{ active: audio.bgmEnabled }"
          type="button"
          :aria-pressed="audio.bgmEnabled"
          @click="audio.toggleBgm"
        >
          <Music2 :size="15" />
        </button>
        <div class="audio-panel__meta">
          <span class="audio-panel__label">背景音乐</span>
          <span class="audio-panel__value audio-percent--bgm">{{ bgmPercent }}%</span>
        </div>
        <input
          class="audio-slider audio-slider--bgm"
          type="range"
          min="0"
          max="1"
          step="0.05"
          :value="audio.bgmVolume"
          aria-label="背景音乐音量"
          @input="audio.setBgmVolume(Number(($event.target as HTMLInputElement).value))"
        />
      </div>

      <div class="audio-panel__row audio-row--sfx">
        <button
          class="audio-icon audio-toggle--sfx-row"
          :class="{ active: audio.sfxEnabled }"
          type="button"
          :aria-pressed="audio.sfxEnabled"
          @click="audio.toggleSfx"
        >
          <Waves :size="15" />
        </button>
        <div class="audio-panel__meta">
          <span class="audio-panel__label">界面音效</span>
          <span class="audio-panel__value audio-percent--sfx">{{ sfxPercent }}%</span>
        </div>
        <input
          class="audio-slider audio-slider--sfx"
          type="range"
          min="0"
          max="1"
          step="0.05"
          :value="audio.sfxVolume"
          aria-label="界面音效音量"
          @input="audio.setSfxVolume(Number(($event.target as HTMLInputElement).value))"
        />
      </div>

      <footer class="audio-panel__foot">
        <button class="audio-mute-all" type="button" @click="audio.toggleAllMute">
          <VolumeX v-if="!allMuted" :size="14" />
          <Volume2 v-else :size="14" />
          {{ allMuted ? '恢复声音' : '全部静音' }}
        </button>
        <span class="audio-panel__note">背景音乐与音效各自独立，互不影响。</span>
      </footer>
    </section>
  </aside>
</template>

<style scoped>
.audio-dock {
  position: fixed;
  right: 22px;
  bottom: 18px;
  z-index: 35;
  height: 44px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: rgba(10, 14, 16, 0.92);
  box-shadow: 0 14px 40px rgba(0, 0, 0, 0.34);
  backdrop-filter: blur(14px);
}

.audio-icon {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: 1px solid var(--line);
  color: var(--muted);
  background: transparent;
  border-radius: 4px;
  cursor: pointer;
}

.audio-icon.active,
.audio-icon:hover {
  color: var(--cyan);
  border-color: rgba(93, 183, 176, 0.35);
}

.audio-status strong,
.audio-status span {
  display: block;
}

.audio-status strong {
  font-size: 10px;
  color: var(--text);
}

.audio-status span {
  margin-top: 2px;
  font-size: 9px;
  color: var(--faint);
}

/* 声音面板：贴在 dock 上方，不遮挡底部内容 */
.audio-panel {
  position: absolute;
  right: 0;
  bottom: calc(100% + 10px);
  width: 288px;
  padding: 14px 14px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: rgba(10, 14, 16, 0.97);
  box-shadow: 0 22px 60px rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(18px);
}

.audio-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.audio-panel__head strong {
  font-size: 11px;
  letter-spacing: 0.06em;
  color: var(--text);
}

.audio-panel__close {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.audio-panel__close:hover {
  color: var(--cyan);
}

.audio-panel__row {
  display: grid;
  grid-template-columns: 28px 1fr;
  grid-template-rows: auto auto;
  align-items: center;
  gap: 6px 10px;
  padding: 9px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

.audio-panel__row .audio-icon {
  grid-row: 1 / span 2;
}

.audio-panel__meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.audio-panel__label {
  font-size: 11px;
  color: var(--text);
}

.audio-panel__value {
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  color: var(--cyan);
}

.audio-slider {
  width: 100%;
  accent-color: var(--cyan);
}

.audio-panel__foot {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-top: 6px;
  padding-top: 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

.audio-mute-all {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 28px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: transparent;
  color: var(--muted);
  font-size: 11px;
  cursor: pointer;
}

.audio-mute-all:hover {
  color: var(--cyan);
  border-color: rgba(93, 183, 176, 0.35);
}

.audio-panel__note {
  font-size: 9px;
  line-height: 1.5;
  color: var(--faint);
}

@media (max-width: 900px) {
  .audio-dock {
    right: 12px;
    bottom: 12px;
  }

  .audio-status {
    display: none;
  }

  .audio-panel {
    width: min(288px, calc(100vw - 24px));
  }
}
</style>
