import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi, userApi } from '@/api'
import type { UserProfile } from '@/types'

const TOKEN_KEY = 'mindtrace_token'
const PROFILE_KEY = 'mindtrace_profile'

function loadProfile(): UserProfile | null {
  try {
    const raw = localStorage.getItem(PROFILE_KEY)
    return raw ? (JSON.parse(raw) as UserProfile) : null
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const profile = ref<UserProfile | null>(loadProfile())
  const ready = ref(false)
  const isLoggedIn = computed(() => Boolean(token.value && profile.value))

  function persist() {
    if (token.value) {
      localStorage.setItem(TOKEN_KEY, token.value)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
    if (profile.value) {
      localStorage.setItem(PROFILE_KEY, JSON.stringify(profile.value))
    } else {
      localStorage.removeItem(PROFILE_KEY)
    }
  }

  async function login(payload: { username: string; password: string }) {
    const result = await authApi.login(payload)
    token.value = result.token
    profile.value = result.profile
    persist()
  }

  async function register(payload: { username: string; nickname: string; password: string }) {
    const result = await authApi.register(payload)
    token.value = result.token
    profile.value = result.profile
    persist()
  }

  async function refresh() {
    if (!token.value) {
      ready.value = true
      return
    }
    try {
      profile.value = await userApi.profile()
      persist()
    } catch {
      token.value = ''
      profile.value = null
      persist()
    } finally {
      ready.value = true
    }
  }

  async function logout() {
    try {
      await authApi.logout()
    } finally {
      token.value = ''
      profile.value = null
      persist()
    }
  }

  return { token, profile, ready, isLoggedIn, login, register, refresh, logout }
})

