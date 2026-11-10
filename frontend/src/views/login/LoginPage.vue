<template>
  <div ref="pageRef" class="login-page">
    <!-- 背景层：网格 + 双色辉光 -->
    <div class="lp-bg" aria-hidden="true">
      <span class="lp-grid" />
      <span class="lp-glow-a" />
      <span class="lp-glow-b" />
    </div>

    <div class="lp-shell">
      <!-- 品牌区 -->
      <div class="lp-brand">
        <span class="lp-mark">
          <svg viewBox="0 0 24 24" width="26" height="26" fill="none">
            <path d="M4 15.5 9.2 9l3.6 4.2L20 5" stroke="url(#lg1)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M4 20h16" stroke="url(#lg1)" stroke-width="1.4" stroke-linecap="round" opacity=".45" />
            <defs>
              <linearGradient id="lg1" x1="0" y1="0" x2="24" y2="24" gradientUnits="userSpaceOnUse">
                <stop stop-color="#38bdf8" />
                <stop offset="1" stop-color="#a78bfa" />
              </linearGradient>
            </defs>
          </svg>
        </span>
        <h1 class="lp-title">复利 <em>TERMINAL</em></h1>
        <p class="lp-tagline">交易记录 · 复盘笔记 · 风控预警 · 绩效归因</p>
      </div>

      <!-- 表单卡 -->
      <div class="lp-card">
        <span class="lp-topline" aria-hidden="true" />
        <div class="lp-card-head">
          <h2>{{ isRegister ? '创建账户' : '登录终端' }}</h2>
          <p>{{ isRegister ? '注册后即可开始记录你的交易' : '欢迎回来，继续你的复盘' }}</p>
        </div>

        <a-form :model="formState" name="login" layout="vertical" @finish="onFinish">
          <a-form-item
            label="用户名"
            name="username"
            :rules="[{ required: true, message: '请输入用户名' }]"
          >
            <a-input
              v-model:value="formState.username"
              :placeholder="isRegister ? '请输入用户名（至少3位）' : '请输入用户名'"
              size="large"
            >
              <template #prefix><AppIcon name="user" :size="14" /></template>
            </a-input>
          </a-form-item>

          <a-form-item
            label="密码"
            name="password"
            :rules="[{ required: true, message: '请输入密码' }, { min: 6, message: '密码至少6位' }]"
          >
            <a-input-password v-model:value="formState.password" placeholder="请输入密码" size="large">
              <template #prefix><AppIcon name="lock" :size="14" /></template>
            </a-input-password>
          </a-form-item>

          <a-form-item
            v-if="isRegister"
            label="确认密码"
            name="confirmPassword"
            :rules="[{ required: true, message: '请确认密码' }, { validator: validateConfirmPassword }]"
          >
            <a-input-password v-model:value="formState.confirmPassword" placeholder="请再次输入密码" size="large">
              <template #prefix><AppIcon name="lock" :size="14" /></template>
            </a-input-password>
          </a-form-item>

          <a-button class="lp-submit" type="primary" html-type="submit" block size="large" :loading="loading">
            {{ isRegister ? '注册账户' : '进入终端' }}
          </a-button>

          <button class="lp-switch" type="button" @click="toggleMode">
            {{ isRegister ? '已有账号？返回登录' : '没有账号？创建一个' }}
          </button>
        </a-form>
      </div>

      <p class="lp-foot mono">NEON EDITION · v2.0</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { loginApi, registerApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import AppIcon from '@/components/ui/AppIcon.vue'
import { revealIn } from '@/theme/motion'

const router = useRouter()
const userStore = useUserStore()
const pageRef = ref<HTMLElement | null>(null)
const loading = ref(false)
const isRegister = ref(false)

const formState = reactive({
  // 记住上次登录的用户名：令牌过期后重新登录只需补密码，不用重敲账号
  username: localStorage.getItem('lastUsername') || '',
  password: '',
  confirmPassword: ''
})

const validateConfirmPassword = async (_rule: any, value: string) => {
  if (value && value !== formState.password) {
    return Promise.reject('两次输入的密码不一致')
  }
  return Promise.resolve()
}

const toggleMode = () => {
  isRegister.value = !isRegister.value
  formState.username = ''
  formState.password = ''
  formState.confirmPassword = ''
}

const onFinish = async (values: { username: string; password: string }) => {
  loading.value = true
  try {
    if (isRegister.value) {
      const res = await registerApi({ username: values.username, password: values.password })
      if (res.code === 200) {
        message.success('注册成功，请登录')
        isRegister.value = false
        formState.password = ''
        formState.confirmPassword = ''
      }
    } else {
      const res = await loginApi(values)
      if (res.code === 200) {
        userStore.setToken(res.data.token, res.data.username, res.data.nickname ?? null, res.data.userId, res.data.cash)
        // 记住用户名：令牌过期后重新登录只需补密码（密码永不落本地）
        localStorage.setItem('lastUsername', values.username)
        message.success('登录成功')
        router.push('/dashboard')
      }
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  revealIn(pageRef.value, '.lp-brand, .lp-card', { y: 16, stagger: 0.08 })
})
</script>

<style scoped>
.login-page {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  background: var(--term-bg);
  overflow: hidden;
}

/* 背景 */
.lp-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.lp-grid {
  position: absolute;
  inset: -10%;
  background-image:
    linear-gradient(var(--term-grid-line) 1px, transparent 1px),
    linear-gradient(90deg, var(--term-grid-line) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(60% 60% at 50% 45%, #000 0%, transparent 78%);
  -webkit-mask-image: radial-gradient(60% 60% at 50% 45%, #000 0%, transparent 78%);
}

.lp-glow-a,
.lp-glow-b {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.5;
}

.lp-glow-a {
  width: 520px;
  height: 520px;
  left: 8%;
  top: -16%;
  background: rgba(56, 189, 248, 0.16);
}

.lp-glow-b {
  width: 460px;
  height: 460px;
  right: 6%;
  bottom: -18%;
  background: rgba(167, 139, 250, 0.14);
}

/* 内容 */
.lp-shell {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 384px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
}

.lp-brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
}

.lp-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 13px;
  background: rgba(56, 189, 248, 0.09);
  border: 1px solid rgba(56, 189, 248, 0.3);
  box-shadow: 0 0 28px rgba(56, 189, 248, 0.18);
}

.lp-title {
  margin: 0;
  font-size: 21px;
  font-weight: 650;
  letter-spacing: 0.6px;
  color: var(--term-fg);
}

.lp-title em {
  font-family: var(--term-font-mono);
  font-style: normal;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 2.4px;
  vertical-align: 2px;
  margin-left: 4px;
  background: var(--term-grad);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.lp-tagline {
  margin: 0;
  font-size: 11.5px;
  letter-spacing: 0.7px;
  color: var(--term-fg-dim);
}

/* 表单卡 */
.lp-card {
  position: relative;
  width: 100%;
  padding: 22px 22px 8px;
  background: linear-gradient(180deg, rgba(18, 26, 43, 0.92), rgba(10, 15, 26, 0.94));
  border: 1px solid var(--term-border-strong);
  border-radius: var(--term-radius-lg);
  box-shadow: var(--term-shadow), inset 0 1px 0 rgba(255, 255, 255, 0.03);
  backdrop-filter: blur(14px);
  overflow: hidden;
}

.lp-topline {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 1px;
  background: var(--term-grad-line);
}

.lp-card-head {
  margin-bottom: 16px;
}

.lp-card-head h2 {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 650;
  letter-spacing: 0.4px;
  color: var(--term-fg);
}

.lp-card-head p {
  margin: 0;
  font-size: 11.5px;
  color: var(--term-fg-muted);
}

/* 表单细节 */
.lp-card :deep(.ant-form-item-label > label) {
  font-size: 11.5px;
  letter-spacing: 0.4px;
}

.lp-card :deep(.ant-input-affix-wrapper) {
  padding: 6px 12px;
}

.lp-card :deep(.ant-input-prefix) {
  color: var(--term-fg-dim);
  margin-right: 8px;
}

.lp-submit {
  height: 40px;
  margin-top: 4px;
  font-size: 13.5px;
  letter-spacing: 1px;
}

.lp-switch {
  display: block;
  width: 100%;
  margin: 12px 0 10px;
  padding: 6px;
  background: transparent;
  border: none;
  color: var(--term-fg-muted);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: color var(--term-dur-fast);
}

.lp-switch:hover {
  color: var(--term-accent);
}

.lp-foot {
  font-size: 10px;
  letter-spacing: 2px;
  color: var(--term-fg-dim);
  opacity: 0.6;
}
</style>
