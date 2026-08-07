<template>
  <div class="login-container">
    <a-card class="login-card" :title="isRegister ? '用户注册' : '股票复盘系统'">
      <a-form :model="formState" name="login" @finish="onFinish" layout="vertical">
        <a-form-item v-if="isRegister" label="用户名" name="username" :rules="[{ required: true, message: '请输入用户名' }]">
          <a-input v-model:value="formState.username" placeholder="请输入用户名（至少3位）" size="large">
            <template #prefix><UserOutlined /></template>
          </a-input>
        </a-form-item>
        <a-form-item v-if="!isRegister" label="用户名" name="username" :rules="[{ required: true, message: '请输入用户名' }]">
          <a-input v-model:value="formState.username" placeholder="请输入用户名" size="large">
            <template #prefix><UserOutlined /></template>
          </a-input>
        </a-form-item>
        <a-form-item label="密码" name="password" :rules="[{ required: true, message: '请输入密码' }, { min: 6, message: '密码至少6位' }]">
          <a-input-password v-model:value="formState.password" placeholder="请输入密码" size="large">
            <template #prefix><LockOutlined /></template>
          </a-input-password>
        </a-form-item>
        <a-form-item v-if="isRegister" label="确认密码" name="confirmPassword" :rules="[{ required: true, message: '请确认密码' }, { validator: validateConfirmPassword }]">
          <a-input-password v-model:value="formState.confirmPassword" placeholder="请再次输入密码" size="large">
            <template #prefix><LockOutlined /></template>
          </a-input-password>
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" block size="large" :loading="loading">
            {{ isRegister ? '注册' : '登录' }}
          </a-button>
        </a-form-item>
        <a-form-item>
          <a-button type="link" block @click="toggleMode">
            {{ isRegister ? '已有账号？去登录' : '没有账号？去注册' }}
          </a-button>
        </a-form-item>
      </a-form>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'
import { loginApi, registerApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const isRegister = ref(false)

const formState = reactive({
  username: '',
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
        userStore.setToken(res.data.token, res.data.username, res.data.nickname ?? null, 0, res.data.cash)
        message.success('登录成功')
        router.push('/dashboard')
      }
    }
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-card {
  width: 400px;
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
}
</style>
