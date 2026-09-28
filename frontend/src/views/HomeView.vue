<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ArrowRight, Archive, Coins, FolderCheck, FolderSearch, ScanSearch, Sparkles, Trophy } from 'lucide-vue-next'
import { caseApi } from '@/api'
import CaseCover from '@/components/CaseCover.vue'
import MaterialTag from '@/components/MaterialTag.vue'
import { useAuthStore } from '@/stores/auth'
import type { CaseSummary } from '@/types'

const auth = useAuthStore()
const cases = ref<CaseSummary[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    cases.value = await caseApi.list()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="home-page">
    <section class="archive-hero">
      <div class="hero-grid" aria-hidden="true"></div>
      <div class="hero-content">
        <span class="eyebrow mono">MINDTRACE / AI INVESTIGATION ARCHIVE</span>
        <h1>每一份档案，<br />都可能隐藏着另一种真相。</h1>
        <p>
          进入真实悬案公开档案，在有限线索、时间矛盾和人物边界中建立你的推理链。
          这不是一段普通对话，而是一场由数据库、规则和 Agent 共同支撑的调查。
        </p>
        <div class="hero-actions">
          <RouterLink class="primary-action" to="/cases"><ScanSearch :size="18" />开始调查</RouterLink>
          <RouterLink class="secondary-action" to="/profile">调查员档案 <ArrowRight :size="16" /></RouterLink>
        </div>
      </div>
      <div class="hero-metrics">
        <div><FolderSearch :size="17" /><span>开放案件</span><strong>{{ cases.length || '03' }}</strong></div>
        <div><Sparkles :size="17" /><span>Agent Context</span><strong>ON</strong></div>
        <div><Archive :size="17" /><span>资料来源</span><strong>分级</strong></div>
      </div>
    </section>

    <div class="page-wrap home-content">
      <section v-reveal class="investigator-strip panel">
        <div class="profile-seal">{{ auth.profile?.nickname?.slice(0, 1) }}</div>
        <div>
          <span class="eyebrow">INVESTIGATOR STATUS</span>
          <h2>{{ auth.profile?.nickname }}，欢迎回到档案馆</h2>
          <p>当前等级 LV.{{ auth.profile?.level }}，累计总分 {{ auth.profile?.totalScore }}。</p>
        </div>
        <div class="stat-cell"><Trophy :size="17" /><span>完成案件</span><strong>{{ auth.profile?.completedCases }}</strong></div>
        <div class="stat-cell"><Sparkles :size="17" /><span>经验值</span><strong>{{ auth.profile?.exp }}</strong></div>
        <div class="stat-cell"><Coins :size="17" /><span>调查币</span><strong>{{ auth.profile?.coins }}</strong></div>
      </section>

      <section class="section-block">
        <div class="section-head">
          <div>
            <span class="eyebrow">FEATURED FILES</span>
            <h2>重点档案</h2>
          </div>
          <RouterLink to="/cases">查看全部 <ArrowRight :size="15" /></RouterLink>
        </div>

        <div v-if="loading" class="featured-grid">
          <div v-for="index in 3" :key="index" class="case-skeleton panel"></div>
        </div>
        <div v-else class="featured-grid">
          <RouterLink
            v-for="(item, index) in cases.slice(0, 3)"
            :key="item.id"
            v-reveal="index * 110"
            class="featured-card panel"
            :to="`/case/${item.id}`"
          >
            <CaseCover :src="item.coverUrl" :title="item.title" :code="item.caseCode" />
            <div class="featured-copy">
              <div class="card-tags">
                <MaterialTag type="REAL" />
                <span class="tag">{{ item.caseType }}</span>
                <span class="tag">{{ item.difficulty }}</span>
              </div>
              <span class="mono case-code">{{ item.caseCode }}</span>
              <h3>{{ item.title }}</h3>
              <p>{{ item.summary }}</p>
              <footer>
                <span>{{ item.era }} · {{ item.location }}</span>
                <span><FolderCheck :size="13" />资料度 {{ item.completion }}%</span>
              </footer>
            </div>
          </RouterLink>
        </div>
      </section>

      <section class="rules-grid">
        <article v-reveal="0" class="panel">
          <span class="rule-index mono">01</span>
          <h3>数据库保存事实</h3>
          <p>时间线、来源、人物和线索均按 REAL、ADAPTED、FICTIONAL 分层保存。</p>
        </article>
        <article v-reveal="110" class="panel">
          <span class="rule-index mono">02</span>
          <h3>Java 控制规则</h3>
          <p>线索解锁、谜题校验、评分、经验与排行榜不交给 AI 临时决定。</p>
        </article>
        <article v-reveal="220" class="panel">
          <span class="rule-index mono">03</span>
          <h3>AI 负责交互</h3>
          <p>DeepSeek 接收受约束的案件上下文，只解释、评估和角色扮演。</p>
        </article>
      </section>
    </div>
  </div>
</template>

<style scoped>
.archive-hero {
  position: relative;
  min-height: 610px;
  display: flex;
  align-items: center;
  padding: 90px max(46px, calc((100vw - 1480px) / 2 + 24px)) 120px;
  overflow: hidden;
  border-bottom: 1px solid var(--line);
  background:
    linear-gradient(90deg, rgba(6, 8, 10, 0.97) 0%, rgba(6, 8, 10, 0.75) 48%, rgba(6, 8, 10, 0.35) 100%),
    linear-gradient(0deg, rgba(5, 7, 8, 0.98), transparent 70%),
    url("/images/archive-room.svg") center 44% / cover no-repeat,
    #0d1215;
}

.hero-grid {
  position: absolute;
  inset: 0;
  opacity: 0.22;
  background:
    linear-gradient(rgba(117, 151, 153, 0.11) 1px, transparent 1px),
    linear-gradient(90deg, rgba(117, 151, 153, 0.11) 1px, transparent 1px);
  background-size: 62px 62px;
  mask-image: linear-gradient(90deg, black, transparent 78%);
}

.hero-content {
  position: relative;
  z-index: 2;
  max-width: 760px;
}

.hero-content h1 {
  margin: 14px 0 22px;
  font-size: clamp(44px, 6vw, 86px);
  line-height: 1.04;
  letter-spacing: 0;
}

.hero-content p {
  max-width: 660px;
  margin: 0;
  color: #a8b4b5;
  font-size: 15px;
  line-height: 1.9;
}

.hero-actions {
  display: flex;
  gap: 10px;
  margin-top: 32px;
}

.primary-action,
.secondary-action {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: 13px;
}

.primary-action {
  color: #d9f4f1;
  border-color: rgba(93, 183, 176, 0.48);
  background: rgba(61, 132, 127, 0.42);
}

.secondary-action {
  color: var(--muted);
  background: rgba(7, 10, 12, 0.5);
}

.hero-metrics {
  position: absolute;
  right: max(46px, calc((100vw - 1480px) / 2 + 24px));
  bottom: 34px;
  z-index: 2;
  display: flex;
  gap: 8px;
}

.hero-metrics div {
  min-width: 118px;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 2px 8px;
  padding: 10px;
  border: 1px solid var(--line);
  background: rgba(7, 10, 12, 0.72);
  backdrop-filter: blur(12px);
  border-radius: 5px;
}

.hero-metrics svg {
  grid-row: 1 / 3;
  color: var(--cyan);
}

.hero-metrics span {
  color: var(--faint);
  font-size: 9px;
}

.hero-metrics strong {
  font-family: "Cascadia Mono", monospace;
  font-size: 14px;
}

.home-content {
  display: grid;
  gap: 62px;
}

.investigator-strip {
  display: grid;
  grid-template-columns: 54px minmax(260px, 1fr) repeat(3, minmax(120px, auto));
  align-items: center;
  gap: 18px;
  padding: 18px 20px;
}

.profile-seal {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(93, 183, 176, 0.32);
  color: var(--cyan);
  background: var(--cyan-soft);
  border-radius: 6px;
  font-size: 18px;
}

.investigator-strip h2 {
  margin: 4px 0 0;
  font-size: 18px;
}

.investigator-strip p {
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 11px;
}

.stat-cell {
  min-width: 120px;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 2px 8px;
  padding-left: 18px;
  border-left: 1px solid var(--line);
}

.stat-cell svg {
  grid-row: 1 / 3;
  color: var(--amber);
}

.stat-cell span {
  color: var(--faint);
  font-size: 9px;
}

.stat-cell strong {
  font-family: "Cascadia Mono", monospace;
  font-size: 17px;
}

.section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 18px;
}

.section-head h2 {
  margin: 5px 0 0;
  font-size: 28px;
}

.section-head a {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--cyan);
  font-size: 12px;
}

.featured-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.featured-card {
  overflow: hidden;
  transition: transform 0.22s ease, border-color 0.22s ease;
}

.featured-card:hover {
  transform: translateY(-3px);
  border-color: var(--line-strong);
}

.featured-copy {
  padding: 16px;
}

.card-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.case-code {
  display: block;
  margin-top: 13px;
  color: var(--cyan);
  font-size: 9px;
}

.featured-copy h3 {
  margin: 6px 0 8px;
  font-size: 21px;
}

.featured-copy p {
  min-height: 66px;
  margin: 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.7;
}

.featured-copy footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
  color: var(--faint);
  font-size: 9px;
}

.featured-copy footer span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.case-skeleton {
  min-height: 420px;
  animation: pulse 1.5s infinite alternate;
}

.rules-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}

.rules-grid article {
  position: relative;
  min-height: 170px;
  padding: 22px;
}

.rule-index {
  color: var(--red);
  font-size: 11px;
}

.rules-grid h3 {
  margin: 28px 0 7px;
  font-size: 17px;
}

.rules-grid p {
  margin: 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.7;
}

@keyframes pulse {
  from { opacity: 0.45; }
  to { opacity: 0.8; }
}

@media (max-width: 1100px) {
  .featured-grid,
  .rules-grid {
    grid-template-columns: 1fr 1fr;
  }

  .investigator-strip {
    grid-template-columns: 54px 1fr repeat(2, auto);
  }

  .stat-cell:last-child {
    display: none;
  }
}

@media (max-width: 760px) {
  .archive-hero {
    min-height: 560px;
    padding: 70px 22px 150px;
  }

  .hero-content h1 {
    font-size: 43px;
  }

  .hero-metrics {
    right: 20px;
    left: 20px;
    bottom: 22px;
    display: grid;
    grid-template-columns: repeat(3, 1fr);
  }

  .hero-metrics div {
    min-width: 0;
  }

  .investigator-strip {
    grid-template-columns: 48px 1fr;
  }

  .stat-cell {
    display: none;
  }

  .featured-grid,
  .rules-grid {
    grid-template-columns: 1fr;
  }
}
</style>

