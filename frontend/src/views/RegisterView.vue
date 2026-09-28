<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Archive, BadgeCheck, KeyRound, UserRound } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const loading = ref(false)
const form = reactive({
  username: '',
  nickname: '',
  password: '',
  confirm: '',
})

async function submit() {
  if (!form.username || !form.nickname || !form.password) {
    ElMessage.warning('请完整填写注册信息')
    return
  }
  if (form.password !== form.confirm) {
    ElMessage.error('两次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    await auth.register({
      username: form.username,
      nickname: form.nickname,
      password: form.password,
    })
    ElMessage.success('调查员档案已建立')
    await router.push('/home')
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="register-page">
    <section class="register-panel">
      <div class="register-brand">
        <span><Archive :size="22" /></span>
        <div>
          <strong>MINDTRACE</strong>
          <small>INVESTIGATOR ENROLLMENT</small>
        </div>
      </div>

      <div class="register-heading">
        <span class="eyebrow">NEW CASE FILE</span>
        <h1>建立调查员档案</h1>
        <p>你的调查历史、案件完成记录和线索发现会与这个身份绑定。</p>
      </div>

      <form @submit.prevent="submit">
        <div class="two-columns">
          <label>
            <span>登录名</span>
            <el-input v-model="form.username" size="large" :prefix-icon="UserRound" placeholder="3-20 位字母数字" />
          </label>
          <label>
            <span>调查员昵称</span>
            <el-input v-model="form.nickname" size="large" :prefix-icon="BadgeCheck" placeholder="排行榜显示名称" />
          </label>
        </div>
        <label>
          <span>密码</span>
          <el-input v-model="form.password" size="large" type="password" show-password :prefix-icon="KeyRound" />
        </label>
        <label>
          <span>确认密码</span>
          <el-input v-model="form.confirm" size="large" type="password" show-password :prefix-icon="KeyRound" />
        </label>
        <p class="password-note">密码使用 BCrypt 哈希保存，后端不会存储明文密码。</p>
        <el-button type="primary" native-type="submit" size="large" :loading="loading">创建档案并进入档案馆</el-button>
      </form>

      <p class="switch-auth">已有调查员档案？<RouterLink to="/login">返回登录</RouterLink></p>
    </section>

    <aside class="register-aside">
      <div class="file-stamp">CLASSIFIED</div>
      <span class="eyebrow">RULES OF INVESTIGATION</span>
      <h2>数据库保存事实。<br />Java 控制规则。<br />AI 只负责交互。</h2>
      <ul>
        <li>真实案件保留现实中的不确定性与争议。</li>
        <li>隐藏线索只能由调查行为和谜题解锁。</li>
        <li>NPC 只回答其知识边界内的内容。</li>
        <li>AI 失败不会导致案件进度丢失。</li>
      </ul>
    </aside>
  </div>
</template>

<style scoped>
.register-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(360px, 0.72fr);
  background: #090c0f;
}

.register-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(36px, 8vw, 110px);
}

.register-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 64px;
}

.register-brand > span {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  color: var(--cyan);
  border: 1px solid rgba(93, 183, 176, 0.35);
  background: var(--cyan-soft);
  border-radius: 6px;
}

.register-brand strong,
.register-brand small {
  display: block;
}

.register-brand strong {
  font-family: "Cascadia Mono", monospace;
  letter-spacing: 0.12em;
}

.register-brand small {
  margin-top: 3px;
  color: var(--faint);
  font-size: 9px;
}

.register-heading h1 {
  margin: 8px 0 9px;
  font-size: clamp(32px, 4vw, 52px);
}

.register-heading p {
  margin: 0 0 28px;
  color: var(--muted);
  line-height: 1.7;
}

form {
  display: grid;
  gap: 15px;
  max-width: 760px;
}

form label > span {
  display: block;
  margin-bottom: 7px;
  color: var(--muted);
  font-size: 11px;
}

.two-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.password-note {
  margin: 0;
  color: var(--faint);
  font-size: 10px;
}

form :deep(.el-button) {
  width: 100%;
  margin-top: 4px;
}

.switch-auth {
  margin-top: 24px;
  color: var(--faint);
  font-size: 11px;
}

.switch-auth a {
  margin-left: 5px;
  color: var(--cyan);
}

.register-aside {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  padding: 8vw 6vw;
  overflow: hidden;
  border-left: 1px solid var(--line);
  background:
    linear-gradient(0deg, rgba(5, 7, 8, 0.92), rgba(5, 7, 8, 0.28)),
    linear-gradient(135deg, rgba(93, 183, 176, 0.12), transparent 45%),
    repeating-linear-gradient(90deg, transparent 0 48px, rgba(255, 255, 255, 0.025) 48px 49px),
    #111619;
}

.file-stamp {
  position: absolute;
  top: 9vw;
  right: 4vw;
  padding: 10px 14px;
  color: rgba(184, 93, 86, 0.65);
  border: 2px solid rgba(184, 93, 86, 0.45);
  transform: rotate(-7deg);
  font-family: "Cascadia Mono", monospace;
  font-size: 12px;
  letter-spacing: 0.16em;
}

.register-aside h2 {
  margin: 10px 0 26px;
  font-size: clamp(28px, 3.4vw, 46px);
  line-height: 1.25;
}

.register-aside ul {
  display: grid;
  gap: 10px;
  margin: 0;
  padding-left: 18px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.55;
}

@media (max-width: 900px) {
  .register-page {
    grid-template-columns: 1fr;
  }

  .register-panel {
    padding: 40px 24px;
  }

  .register-aside {
    min-height: 420px;
    padding: 54px 28px;
    border-left: 0;
    border-top: 1px solid var(--line);
  }
}

@media (max-width: 620px) {
  .two-columns {
    grid-template-columns: 1fr;
  }
}
</style>

