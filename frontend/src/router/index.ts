import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { isTokenExpired } from '@/utils/jwt'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/LoginPage.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/',
    component: BasicLayout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/DashboardPage.vue'),
        meta: { title: '仪表盘' }
      },
      {
        path: 'trade',
        name: 'TradeList',
        component: () => import('@/views/trade/TradeList.vue'),
        meta: { title: '交易记录' }
      },
      {
        path: 'trade/add',
        name: 'TradeAdd',
        component: () => import('@/views/trade/TradeForm.vue'),
        meta: { title: '新增交易' }
      },
      {
        path: 'kline',
        name: 'KlineChart',
        component: () => import('@/views/stock/KLinePage.vue'),
        meta: { title: 'K线图' }
      },
      {
        path: 'practice',
        name: 'HistoryPractice',
        component: () => import('@/views/practice/PracticePage.vue'),
        meta: { title: '历史交易练习' }
      },
      {
        path: 'journal',
        name: 'JournalPage',
        component: () => import('@/views/journal/JournalPage.vue'),
        meta: { title: '复盘笔记' }
      },
      {
        path: 'alert',
        name: 'AlertPage',
        component: () => import('@/views/alert/AlertPage.vue'),
        meta: { title: '风控预警' }
      },
      {
        path: 'performance',
        name: 'PerformancePage',
        component: () => import('@/views/analysis/PerformancePage.vue'),
        meta: { title: '绩效分析' }
      },
      {
        path: 'report',
        name: 'ReportPage',
        component: () => import('@/views/report/ReportPage.vue'),
        meta: { title: '复盘报告' }
      },
      {
        path: 'quant-ranking',
        name: 'QuantRankingPage',
        component: () => import('@/views/quant/QuantRankingPage.vue'),
        meta: { title: '量化排行' }
      },
      {
        path: 'sync',
        name: 'StockSync',
        component: () => import('@/views/stock/StockSync.vue'),
        meta: { title: '数据同步' }
      },
      {
        path: 'okx',
        name: 'OkxSettings',
        component: () => import('@/views/okx/OkxSettingsPage.vue'),
        meta: { title: 'OKX 账户' }
      },
      {
        path: 'okx/suggestions',
        name: 'OkxSuggestions',
        component: () => import('@/views/okx/OkxSuggestionsPage.vue'),
        meta: { title: '建议单确认' }
      },
      {
        path: 'account',
        name: 'AccountProfile',
        component: () => import('@/views/account/ProfilePage.vue'),
        meta: { title: '账户信息' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('token')
  // 只有「令牌存在但已过期」才主动清理；没有令牌时保持安静（首次访问不该弹提示）
  if (token && isTokenExpired(token)) {
    localStorage.removeItem('token')
    localStorage.removeItem('nickname')
    localStorage.removeItem('userId')
    localStorage.removeItem('cash')
  }
  const valid = !!token && !isTokenExpired(token)
  if (to.path !== '/login' && !valid) {
    next('/login')
  } else if (to.path === '/login' && valid) {
    next('/dashboard')
  } else {
    next()
  }
})

export default router
