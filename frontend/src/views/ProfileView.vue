<script setup lang="ts">
import { computed } from 'vue'
import { Award, BadgeCheck, CalendarDays, Coins, FolderCheck, Medal, Sparkles, Star, Target, Trophy } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const profile = computed(() => auth.profile)
const expPercent = computed(() => {
  if (!profile.value) return 0
  return Math.min(100, Math.round((profile.value.exp / profile.value.nextLevelExp) * 100))
})

const unlockedAchievements = computed(() => profile.value?.achievements.filter((item) => item.unlocked) || [])
</script>

<template>
  <div v-if="profile" class="page-wrap">
    <header class="page-heading">
      <div>
        <span class="eyebrow">INVESTIGATOR DOSSIER</span>
        <h1 class="page-title">调查员档案</h1>
        <p class="page-subtitle">调查等级、案件历史、成就和解锁记录均来自后端持久化数据。</p>
      </div>
    </header>

    <section class="profile-hero panel">
      <div class="large-avatar">{{ profile.nickname.slice(0, 1) }}</div>
      <div class="identity">
        <div class="identity-line">
          <span class="tag tag--ok">ACTIVE INVESTIGATOR</span>
          <span class="mono">ID-{{ String(profile.id).padStart(5, '0') }}</span>
        </div>
        <h2>{{ profile.nickname }}</h2>
        <p>@{{ profile.username }} · 最近登录 {{ profile.lastLoginAt?.replace('T', ' ').slice(0, 16) }}</p>
        <div class="level-line">
          <div>
            <span>LEVEL {{ profile.level }}</span>
            <small>{{ profile.exp }} / {{ profile.nextLevelExp }} EXP</small>
          </div>
          <div class="level-track"><i :style="{ width: `${expPercent}%` }"></i></div>
        </div>
      </div>
      <div class="profile-stats">
        <div><Trophy :size="18" /><span>累计总分</span><strong>{{ profile.totalScore }}</strong></div>
        <div><FolderCheck :size="18" /><span>完成案件</span><strong>{{ profile.completedCases }}</strong></div>
        <div><Coins :size="18" /><span>调查币</span><strong>{{ profile.coins }}</strong></div>
        <div><CalendarDays :size="18" /><span>连续调查</span><strong>{{ profile.streakDays }} 天</strong></div>
      </div>
    </section>

    <section class="profile-grid">
      <div class="panel history-panel">
        <header class="panel-head">
          <div>
            <span class="eyebrow">GAME RECORDS</span>
            <h2>调查历史</h2>
          </div>
          <Target :size="21" />
        </header>
        <div v-if="profile.history.length" class="history-list">
          <RouterLink v-for="record in profile.history" :key="record.recordId" :to="`/case/${record.caseId}`">
            <div>
              <span class="mono">{{ record.caseCode }}</span>
              <h3>{{ record.caseTitle }}</h3>
            </div>
            <div class="history-score">
              <span>{{ record.status === 'COMPLETED' ? '已结案' : '调查中' }}</span>
              <strong>{{ record.totalScore }}</strong>
            </div>
          </RouterLink>
        </div>
        <div v-else class="empty-history">
          <FolderCheck :size="28" />
          <p>还没有结案记录。选择一个案件开始调查。</p>
          <RouterLink to="/cases">前往案件档案</RouterLink>
        </div>
      </div>

      <div class="panel achievement-panel">
        <header class="panel-head">
          <div>
            <span class="eyebrow">ACHIEVEMENTS</span>
            <h2>调查成就</h2>
          </div>
          <Medal :size="21" />
        </header>
        <div class="achievement-summary">
          <BadgeCheck :size="23" />
          <span>已解锁 {{ unlockedAchievements.length }} / {{ profile.achievements.length }}</span>
          <RouterLink class="achievement-more" to="/achievements">查看全部徽章</RouterLink>
        </div>
        <div class="achievement-list">
          <article
            v-for="achievement in profile.achievements"
            :key="achievement.id"
            :class="{ locked: !achievement.unlocked }"
          >
            <span class="achievement-icon">
              <Star v-if="achievement.unlocked" :size="17" />
              <Award v-else :size="17" />
            </span>
            <div>
              <strong>{{ achievement.name }}</strong>
              <p>{{ achievement.description }}</p>
            </div>
            <Sparkles v-if="achievement.unlocked" :size="15" />
          </article>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.profile-hero {
  display: grid;
  grid-template-columns: 92px minmax(280px, 1fr) minmax(420px, 0.92fr);
  align-items: center;
  gap: 22px;
  padding: 22px;
}

.large-avatar {
  width: 86px;
  height: 86px;
  display: grid;
  place-items: center;
  color: #d9f4f1;
  background:
    linear-gradient(145deg, rgba(93, 183, 176, 0.5), rgba(184, 93, 86, 0.54)),
    #213432;
  border: 1px solid rgba(93, 183, 176, 0.38);
  border-radius: 8px;
  font-size: 30px;
}

.identity-line {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--faint);
  font-size: 9px;
}

.identity h2 {
  margin: 12px 0 5px;
  font-size: 31px;
}

.identity > p {
  margin: 0;
  color: var(--muted);
  font-size: 11px;
}

.level-line {
  max-width: 480px;
  display: grid;
  grid-template-columns: 1fr;
  gap: 6px;
  margin-top: 16px;
}

.level-line > div:first-child {
  display: flex;
  justify-content: space-between;
  color: var(--cyan);
  font-family: "Cascadia Mono", monospace;
  font-size: 10px;
}

.level-line small {
  color: var(--faint);
}

.level-track {
  height: 6px;
  overflow: hidden;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.06);
}

.level-track i {
  height: 100%;
  display: block;
  background: linear-gradient(90deg, var(--cyan), var(--amber));
}

.profile-stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.profile-stats div {
  min-height: 68px;
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 2px 9px;
  padding: 11px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.018);
  border-radius: 6px;
}

.profile-stats svg {
  grid-row: 1 / 3;
  color: var(--amber);
}

.profile-stats span {
  color: var(--faint);
  font-size: 9px;
}

.profile-stats strong {
  font-family: "Cascadia Mono", monospace;
  font-size: 18px;
}

.profile-grid {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr;
  gap: 15px;
  margin-top: 15px;
}

.history-panel,
.achievement-panel {
  padding: 18px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line);
}

.panel-head h2 {
  margin: 5px 0 0;
  font-size: 21px;
}

.panel-head > svg {
  color: var(--cyan);
}

.history-list {
  display: grid;
}

.history-list a {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 2px;
  border-bottom: 1px solid var(--line);
}

.history-list a:hover h3 {
  color: var(--cyan);
}

.history-list span {
  color: var(--faint);
  font-size: 9px;
}

.history-list h3 {
  margin: 4px 0 0;
  font-size: 14px;
}

.history-score {
  text-align: right;
}

.history-score strong {
  display: block;
  margin-top: 4px;
  color: var(--amber);
  font-family: "Cascadia Mono", monospace;
  font-size: 18px;
}

.empty-history {
  min-height: 270px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--faint);
  text-align: center;
}

.empty-history p {
  font-size: 11px;
}

.empty-history a {
  color: var(--cyan);
  font-size: 11px;
}

.achievement-summary {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 14px 0;
  padding: 11px;
  color: var(--cyan);
  border: 1px solid rgba(93, 183, 176, 0.24);
  background: var(--cyan-soft);
  border-radius: 5px;
  font-size: 11px;
}

.achievement-more {
  margin-left: auto;
  padding: 4px 9px;
  color: #8fd5cc;
  border: 1px solid rgba(93, 183, 176, 0.32);
  border-radius: 4px;
  font-size: 10px;
}

.achievement-more:hover {
  color: var(--text);
  border-color: rgba(93, 183, 176, 0.6);
}

.achievement-list {
  display: grid;
  gap: 8px;
}

.achievement-list article {
  display: grid;
  grid-template-columns: 34px 1fr auto;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 5px;
}

.achievement-list article.locked {
  opacity: 0.42;
}

.achievement-icon {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  color: var(--amber);
  border: 1px solid rgba(204, 155, 87, 0.25);
  background: var(--amber-soft);
  border-radius: 5px;
}

.achievement-list strong {
  font-size: 12px;
}

.achievement-list p {
  margin: 3px 0 0;
  color: var(--faint);
  font-size: 9px;
}

.achievement-list article > svg {
  color: var(--cyan);
}

@media (max-width: 1000px) {
  .profile-hero {
    grid-template-columns: 86px 1fr;
  }

  .profile-stats {
    grid-column: 1 / -1;
  }
}

@media (max-width: 760px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }

  .profile-hero {
    grid-template-columns: 72px 1fr;
    padding: 16px;
  }

  .large-avatar {
    width: 68px;
    height: 68px;
  }

  .identity h2 {
    font-size: 24px;
  }
}
</style>

