import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { guest: true, title: '登录' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { guest: true, title: '注册调查员' },
    },
    {
      path: '/',
      redirect: '/home',
    },
    {
      path: '/home',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { requiresAuth: true, title: '悬疑档案馆' },
    },
    {
      path: '/cases',
      name: 'cases',
      component: () => import('@/views/CasesView.vue'),
      meta: { requiresAuth: true, title: '案件档案' },
    },
    {
      path: '/case/:id',
      name: 'case-detail',
      component: () => import('@/views/CaseDetailView.vue'),
      meta: { requiresAuth: true, title: '调查台' },
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('@/views/ProfileView.vue'),
      meta: { requiresAuth: true, title: '调查员档案' },
    },
    {
      path: '/achievements',
      name: 'achievements',
      component: () => import('@/views/AchievementsView.vue'),
      meta: { requiresAuth: true, title: '成就徽章' },
    },
    {
      path: '/ranking',
      name: 'ranking',
      component: () => import('@/views/RankingView.vue'),
      meta: { requiresAuth: true, title: '排行榜' },
    },
    {
      path: '/:pathMatch(.*)*',
      component: () => import('@/views/NotFoundView.vue'),
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.ready) {
    await auth.refresh()
  }
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.guest && auth.isLoggedIn) {
    return { name: 'home' }
  }
  document.title = `${String(to.meta.title || 'AI 推理档案')} · MindTrace`
  return true
})

export default router

