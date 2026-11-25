/** 定义页面路由和按需加载入口，并根据登录状态限制访问路径。 */

import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from '../stores/auth'

const routes = [
  { path: '/login', component: () => import('../views/LoginView.vue') },
  { path: '/overview', component: () => import('../views/OverviewView.vue') },
  { path: '/library', component: () => import('../views/LibraryView.vue') },
  { path: '/ask', component: () => import('../views/AskView.vue') },
  { path: '/agent', component: () => import('../views/AgentView.vue') },
  { path: '/traces', component: () => import('../views/TracesView.vue') },
  { path: '/metrics', component: () => import('../views/MetricsView.vue') },
  { path: '/status', component: () => import('../views/StatusView.vue') },
  { path: '/settings', component: () => import('../views/SettingsView.vue') },
  { path: '/account', component: () => import('../views/AccountView.vue') },
  { path: '/', redirect: '/overview' },
  { path: '/:pathMatch(.*)*', component: () => import('../views/NotFoundView.vue') },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const auth = useAuth()
  // 只要还有 access 或 refresh 任一票据就算「已登录」；只剩 refresh 时，第一个请求 401 会触发静默刷新
  const loggedIn = !!auth.token || !!auth.refreshToken
  if (to.path !== '/login' && !loggedIn) return '/login'
  if (to.path === '/login' && loggedIn) return '/overview'
})

export default router
