import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export type AudioTrack = 'lobby' | 'investigation' | 'tension' | 'result'
export type AudioCue = 'clue' | 'confirm' | 'error'

const tracks: Record<AudioTrack, string> = {
  lobby: '/audio/lobby.mp3',
  investigation: '/audio/investigation.mp3',
  tension: '/audio/tension.mp3',
  result: '/audio/result.mp3',
}

/** 音效峰值增益（sfxVolume = 1 时）。0.06 与旧版硬编码的 0.035 听感接近。 */
const CUE_PEAK = 0.06
const CUE_DECAY_SECONDS = 0.16
const CUE_FREQUENCY: Record<AudioCue, number> = { clue: 720, confirm: 440, error: 180 }

function clamp01(value: number) {
  if (!Number.isFinite(value)) return 0
  return Math.min(1, Math.max(0, value))
}

function readVolume(key: string, fallback: number) {
  const raw = localStorage.getItem(key)
  if (raw === null || raw === '') return fallback
  const parsed = Number(raw)
  return Number.isFinite(parsed) ? clamp01(parsed) : fallback
}

export const useAudioStore = defineStore('audio', () => {
  // BGM 与音效完全独立：开关分开、音量分开。
  // 旧版本只有一个共享的 mindtrace_volume，这里读它作为 BGM 音量的回退值，老用户的设置不会丢。
  const bgmEnabled = ref(localStorage.getItem('mindtrace_bgm') !== 'off')
  const sfxEnabled = ref(localStorage.getItem('mindtrace_sfx') !== 'off')
  const bgmVolume = ref(readVolume('mindtrace_bgm_volume', readVolume('mindtrace_volume', 0.25)))
  const sfxVolume = ref(readVolume('mindtrace_sfx_volume', 0.6))

  const currentTrack = ref<AudioTrack | null>(null)
  const trackAvailable = ref(false)
  // 浏览器自动播放策略：用户第一次交互之前 play() 会被拒绝。
  // 这不是「坏了」，而是「等一下就播」，所以单独用一个标志位表达，不混进 trackAvailable。
  const waitingForGesture = ref(false)
  const availableTracks = ref<Record<AudioTrack, boolean>>({
    lobby: false,
    investigation: false,
    tension: false,
    result: false,
  })

  let audio: HTMLAudioElement | null = null
  let cueContext: AudioContext | null = null
  let gestureUnlockBound = false

  const muted = computed(() => !bgmEnabled.value && !sfxEnabled.value)

  const statusText = computed(() => {
    if (!currentTrack.value) return 'BGM 待机'
    if (!bgmEnabled.value) return `${currentTrack.value} · 已静音`
    if (waitingForGesture.value) return `${currentTrack.value} · 等待首次点击`
    if (!trackAvailable.value) return `${currentTrack.value} · 音频清单未启用`
    return `${currentTrack.value} · 播放中`
  })

  const statusHint = computed(() => {
    if (waitingForGesture.value) return '浏览器要求先有一次交互，点击任意位置即可播放'
    if (trackAvailable.value) return '环境音轨已连接'
    return '在 public/audio 放置 MP3 后自动启用'
  })

  function persist() {
    localStorage.setItem('mindtrace_bgm', bgmEnabled.value ? 'on' : 'off')
    localStorage.setItem('mindtrace_sfx', sfxEnabled.value ? 'on' : 'off')
    localStorage.setItem('mindtrace_bgm_volume', String(bgmVolume.value))
    localStorage.setItem('mindtrace_sfx_volume', String(sfxVolume.value))
  }

  /**
   * 等用户第一次点击/按键后再续播。
   * 只挂一次性监听，而不是在自动播放被拒后反复重试 —— 否则控制台会被 NotAllowedError 刷屏。
   */
  function bindGestureUnlock() {
    if (gestureUnlockBound) return
    gestureUnlockBound = true
    const resume = () => {
      document.removeEventListener('pointerdown', resume)
      document.removeEventListener('keydown', resume)
      gestureUnlockBound = false
      waitingForGesture.value = false
      if (bgmEnabled.value) startPlayback()
    }
    document.addEventListener('pointerdown', resume)
    document.addEventListener('keydown', resume)
  }

  function startPlayback() {
    if (!audio) return
    void audio.play().catch((error: unknown) => {
      if (error instanceof DOMException && error.name === 'NotAllowedError') {
        waitingForGesture.value = true
        bindGestureUnlock()
        return
      }
      trackAvailable.value = false
    })
  }

  function updateAudio() {
    persist()
    if (audio) audio.volume = bgmVolume.value
    if (!audio) return
    if (bgmEnabled.value) startPlayback()
    else audio.pause()
  }

  function setTrack(track: AudioTrack) {
    if (currentTrack.value === track && audio) {
      updateAudio()
      return
    }
    currentTrack.value = track
    audio?.pause()
    audio = null
    trackAvailable.value = false
    if (!availableTracks.value[track]) {
      return
    }

    const element = new Audio(tracks[track])
    element.loop = true
    element.preload = 'metadata'
    element.volume = bgmVolume.value
    element.addEventListener('canplaythrough', () => {
      trackAvailable.value = true
      if (bgmEnabled.value) startPlayback()
    })
    element.addEventListener('error', () => {
      trackAvailable.value = false
      element.pause()
    })
    audio = element
    updateAudio()
  }

  async function loadManifest() {
    try {
      const response = await fetch('/audio/manifest.json', { cache: 'no-store' })
      if (!response.ok) return
      const manifest = (await response.json()) as {
        tracks?: Partial<Record<AudioTrack, boolean>>
      }
      availableTracks.value = {
        lobby: Boolean(manifest.tracks?.lobby),
        investigation: Boolean(manifest.tracks?.investigation),
        tension: Boolean(manifest.tracks?.tension),
        result: Boolean(manifest.tracks?.result),
      }
      if (currentTrack.value) {
        const requested = currentTrack.value
        currentTrack.value = null
        setTrack(requested)
      }
    } catch {
      // Missing manifest simply means BGM is not installed.
    }
  }

  function toggleBgm() {
    bgmEnabled.value = !bgmEnabled.value
    updateAudio()
  }

  function toggleSfx() {
    sfxEnabled.value = !sfxEnabled.value
    persist()
  }

  function setBgmVolume(value: number) {
    bgmVolume.value = clamp01(value)
    updateAudio()
  }

  function setSfxVolume(value: number) {
    sfxVolume.value = clamp01(value)
    persist()
  }

  /** 一键静音 / 恢复：只要还有一路在响就全部静音，全静音时才恢复。只翻开关，不动滑块。 */
  function toggleAllMute() {
    const next = !(bgmEnabled.value || sfxEnabled.value)
    bgmEnabled.value = next
    sfxEnabled.value = next
    updateAudio()
  }

  /** 整个会话共用一个 AudioContext：每次音效都新建一个会持续泄漏，浏览器也会限制实例数。 */
  function cueContextOrNull(): AudioContext | null {
    if (cueContext) return cueContext
    try {
      cueContext = new AudioContext()
    } catch {
      return null // 浏览器不支持 WebAudio：静默降级，不影响游戏流程
    }
    return cueContext
  }

  function playCue(kind: AudioCue) {
    if (!sfxEnabled.value || sfxVolume.value <= 0) return
    const context = cueContextOrNull()
    if (!context) return
    // 自动播放策略下 AudioContext 可能是 suspended，resume 之后才真的有声音
    if (context.state === 'suspended') void context.resume().catch(() => undefined)

    const gain = context.createGain()
    const tone = context.createOscillator()
    tone.type = kind === 'error' ? 'sawtooth' : 'sine'
    tone.frequency.value = CUE_FREQUENCY[kind]
    // exponentialRamp 的起点不能是 0，所以音量已经为 0 时在上面直接返回了
    gain.gain.setValueAtTime(CUE_PEAK * sfxVolume.value, context.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + CUE_DECAY_SECONDS)
    tone.connect(gain).connect(context.destination)
    tone.start()
    tone.stop(context.currentTime + CUE_DECAY_SECONDS + 0.01)
    tone.onended = () => {
      tone.disconnect()
      gain.disconnect()
    }
  }

  void loadManifest()

  return {
    bgmEnabled,
    sfxEnabled,
    bgmVolume,
    sfxVolume,
    muted,
    currentTrack,
    trackAvailable,
    waitingForGesture,
    availableTracks,
    statusText,
    statusHint,
    setTrack,
    toggleBgm,
    toggleSfx,
    setBgmVolume,
    setSfxVolume,
    toggleAllMute,
    playCue,
  }
})
