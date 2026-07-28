import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import DashboardView from '../views/DashboardView.vue'
import TradeListView from '../views/TradeListView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView },
    { path: '/dashboard', component: DashboardView },
    { path: '/trades', component: TradeListView },
    { path: '/', redirect: '/login' },
  ],
})

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) {
    return '/login'
  }
  return true
})

export default router
