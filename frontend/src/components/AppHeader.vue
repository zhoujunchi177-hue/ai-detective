<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Archive, BarChart3, FolderSearch, LogOut, Medal, ShieldCheck, UserRound } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const initials = computed(() => auth.profile?.nickname?.slice(0, 1) || '调')

async function logout() {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <header class="app-header">
    <RouterLink to="/home" class="brand">
      <span class="brand-mark"><Archive :size="19" /></span>
      <span>
        <strong>MINDTRACE</strong>
        <small>AI INVESTIGATION ARCHIVE</small>
      </span>
    </RouterLink>

    <nav class="main-nav" aria-label="主导航">
      <RouterLink to="/home"><ShieldCheck :size="16" />档案馆</RouterLink>
      <RouterLink to="/cases"><FolderSearch :size="16" />案件</RouterLink>
      <RouterLink to="/achievements"><Medal :size="16" />成就</RouterLink>
      <RouterLink to="/ranking"><BarChart3 :size="16" />排行</RouterLink>
    </nav>

    <div class="profile-strip">
      <RouterLink to="/profile" class="profile-link">
        <span class="avatar">{{ initials }}</span>
        <span class="profile-copy">
          <strong>{{ auth.profile?.nickname }}</strong>
          <small>LV.{{ auth.profile?.level }} · {{ auth.profile?.coins }} 币</small>
        </span>
        <UserRound :size="16" />
      </RouterLink>
      <button class="icon-button" type="button" title="退出登录" @click="logout">
        <LogOut :size="17" />
      </button>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: 40;
  height: 68px;
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 28px;
  padding: 0 28px;
  border-bottom: 1px solid var(--line);
  background: rgba(8, 11, 13, 0.88);
  backdrop-filter: blur(18px);
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 220px;
}

.brand-mark {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(93, 183, 176, 0.35);
  color: var(--cyan);
  background: var(--cyan-soft);
  border-radius: 6px;
}

.brand strong,
.brand small {
  display: block;
}

.brand strong {
  font-family: "Cascadia Mono", "Consolas", monospace;
  font-size: 13px;
  letter-spacing: 0.12em;
}

.brand small {
  margin-top: 2px;
  color: var(--faint);
  font-size: 8px;
  letter-spacing: 0.1em;
}

.main-nav {
  display: flex;
  align-items: center;
  gap: 6px;
}

.main-nav a {
  min-height: 36px;
  padding: 0 12px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: 1px solid transparent;
  border-radius: 5px;
  color: var(--muted);
  font-size: 13px;
}

.main-nav a:hover,
.main-nav a.router-link-active {
  color: var(--text);
  border-color: var(--line);
  background: rgba(255, 255, 255, 0.035);
}

.main-nav a.router-link-active {
  color: #8fd5cc;
}

.profile-strip,
.profile-link {
  display: flex;
  align-items: center;
}

.profile-strip {
  gap: 7px;
}

.profile-link {
  gap: 9px;
  padding: 5px 8px;
  border-radius: 6px;
}

.profile-link:hover {
  background: rgba(255, 255, 255, 0.035);
}

.avatar {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 5px;
  background: linear-gradient(145deg, #355d5b, #7c4f45);
  font-size: 13px;
  font-weight: 700;
}

.profile-copy strong,
.profile-copy small {
  display: block;
}

.profile-copy strong {
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}

.profile-copy small {
  margin-top: 2px;
  color: var(--faint);
  font-size: 10px;
}

.icon-button {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 1px solid var(--line);
  color: var(--muted);
  background: transparent;
  border-radius: 5px;
  cursor: pointer;
}

.icon-button:hover {
  color: var(--red);
  border-color: rgba(184, 93, 86, 0.4);
}

@media (max-width: 900px) {
  .app-header {
    height: auto;
    min-height: 64px;
    grid-template-columns: 1fr auto;
    gap: 12px;
    padding: 10px 16px;
  }

  .brand {
    min-width: 0;
  }

  .main-nav {
    order: 3;
    grid-column: 1 / -1;
    justify-content: space-between;
    overflow-x: auto;
  }

  .main-nav a {
    flex: 1;
    justify-content: center;
    white-space: nowrap;
  }

  .profile-copy {
    display: none;
  }
}
</style>

