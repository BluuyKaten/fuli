import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import BasicLayout from '@/layouts/BasicLayout.vue'

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
        path: 'sync',
        name: 'StockSync',
        component: () => import('@/views/stock/StockSync.vue'),
        meta: { title: '数据同步' }
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
  if (to.path !== '/login' && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    next('/dashboard')
  } else {
    next()
  }
})

export default router
