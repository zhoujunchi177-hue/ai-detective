<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Archive, KeyRound, ScanLine, UserRound } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form)
    ElMessage.success('身份验证通过')
    await router.push(String(route.query.redirect || '/home'))
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-visual">
      <div class="visual-grid"></div>
      <div class="visual-copy">
        <span class="eyebrow mono">MINDTRACE / RESTRICTED ARCHIVE</span>
        <h1>真相不会主动出现，<br />你需要自己推理。</h1>
        <p>
          在公开案件资料、时间线、人物证词和有限线索之间建立连接。
          AI 是调查世界的一部分，但不是案件的真相来源。
        </p>
        <div class="visual-tags">
          <span><ScanLine :size="14" />Agent Context</span>
          <span><Archive :size="14" />Real Case Archive</span>
        </div>
      </div>
    </div>

    <section class="auth-panel">
      <div class="auth-brand">
        <span><Archive :size="22" /></span>
        <div>
          <strong>MINDTRACE</strong>
          <small>AI 推理档案</small>
        </div>
      </div>
      <div class="auth-heading">
        <span class="eyebrow">INVESTIGATOR ACCESS</span>
        <h2>调查员登录</h2>
        <p>恢复你的案件进度、线索档案和调查等级。</p>
      </div>

      <form @submit.prevent="submit">
        <label>
          <span>用户名</span>
          <el-input v-model="form.username" size="large" placeholder="输入用户名" :prefix-icon="UserRound" />
        </label>
        <label>
          <span>密码</span>
          <el-input
            v-model="form.password"
            size="large"
            type="password"
            show-password
            placeholder="输入密码"
            :prefix-icon="KeyRound"
          />
        </label>
        <el-alert
          v-if="route.query.expired"
          title="登录状态已过期，请重新验证。"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-button type="primary" native-type="submit" size="large" :loading="loading">进入档案系统</el-button>
      </form>

      <p class="switch-auth">
        还没有调查员身份？
        <RouterLink to="/register">创建新档案</RouterLink>
      </p>
      <p class="security-note">AI Key 仅保存在 Java 后端环境变量中，浏览器无法读取。</p>
    </section>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(390px, 0.7fr);
  background: var(--ink-0);
}

.auth-visual {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: flex-end;
  padding: 8vw;
  overflow: hidden;
  border-right: 1px solid var(--line);
  background:
    linear-gradient(90deg, rgba(4, 6, 7, 0.3), rgba(4, 6, 7, 0.82)),
    linear-gradient(0deg, rgba(3, 5, 6, 0.96), rgba(3, 5, 6, 0.1)),
    url("/images/archive-room.svg") center / cover no-repeat,
    #0a0f12;
}

.visual-grid {
  position: absolute;
  inset: 0;
  opacity: 0.28;
  background:
    linear-gradient(rgba(93, 183, 176, 0.1) 1px, transparent 1px),
    linear-gradient(90deg, rgba(93, 183, 176, 0.1) 1px, transparent 1px);
  background-size: 52px 52px;
  mask-image: linear-gradient(180deg, black, transparent 82%);
}

.visual-copy {
  position: relative;
  z-index: 2;
  max-width: 760px;
}

.visual-copy h1 {
  margin: 12px 0 20px;
  font-size: clamp(42px, 5.2vw, 78px);
  line-height: 1.08;
  letter-spacing: 0;
}

.visual-copy p {
  max-width: 590px;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.9;
}

.visual-tags {
  display: flex;
  gap: 9px;
  margin-top: 28px;
}

.visual-tags span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  border: 1px solid var(--line);
  color: var(--muted);
  background: rgba(7, 10, 12, 0.52);
  border-radius: 4px;
  font-size: 11px;
}

.auth-panel {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(36px, 6vw, 86px);
  background:
    linear-gradient(145deg, rgba(93, 183, 176, 0.045), transparent 40%),
    #0b0f12;
}

.auth-brand {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-bottom: 72px;
}

.auth-brand > span {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  color: var(--cyan);
  border: 1px solid rgba(93, 183, 176, 0.35);
  background: var(--cyan-soft);
  border-radius: 6px;
}

.auth-brand strong,
.auth-brand small {
  display: block;
}

.auth-brand strong {
  font-family: "Cascadia Mono", monospace;
  font-size: 15px;
  letter-spacing: 0.12em;
}

.auth-brand small {
  margin-top: 3px;
  color: var(--faint);
  font-size: 10px;
}

.auth-heading h2 {
  margin: 8px 0 8px;
  font-size: 32px;
}

.auth-heading p {
  margin: 0 0 28px;
  color: var(--muted);
  font-size: 12px;
}

form {
  display: grid;
  gap: 17px;
}

form label > span {
  display: block;
  margin-bottom: 7px;
  color: var(--muted);
  font-size: 11px;
}

form :deep(.el-button) {
  width: 100%;
  margin-top: 4px;
}

.switch-auth {
  margin: 24px 0 0;
  color: var(--faint);
  text-align: center;
  font-size: 11px;
}

.switch-auth a {
  margin-left: 5px;
  color: var(--cyan);
}

.security-note {
  margin: 34px 0 0;
  color: #5d696b;
  text-align: center;
  font-size: 9px;
}

@media (max-width: 900px) {
  .auth-page {
    grid-template-columns: 1fr;
  }

  .auth-visual {
    min-height: 42vh;
    padding: 56px 26px 36px;
  }

  .visual-copy h1 {
    font-size: 38px;
  }

  .visual-copy p {
    font-size: 13px;
  }

  .auth-panel {
    min-height: 58vh;
    padding: 36px 24px 64px;
  }

  .auth-brand {
    margin-bottom: 38px;
  }
}
</style>

