<template>
  <a-card title="股票复盘系统登录" style="max-width: 420px; margin: 80px auto;">
    <a-form layout="vertical" @finish="onSubmit">
      <a-form-item label="用户名" name="username" :rules="[{ required: true, message: '请输入用户名' }]">
        <a-input v-model:value="form.username" />
      </a-form-item>
      <a-form-item label="密码" name="password" :rules="[{ required: true, message: '请输入密码' }]">
        <a-input-password v-model:value="form.password" />
      </a-form-item>
      <a-button type="primary" html-type="submit" block>登录</a-button>
    </a-form>
  </a-card>
</template>

<script setup lang="ts">
import { reactive } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { Result } from '../types/result'

interface TokenVO {
  token: string
}

const router = useRouter()
const authStore = useAuthStore()
const form = reactive({ username: '', password: '' })

const onSubmit = async () => {
  const { data } = await http.post<Result<TokenVO>>('/api/auth/login', form)
  if (data.code === 200 && data.data?.token) {
    authStore.setToken(data.data.token)
    message.success('登录成功')
    router.push('/dashboard')
  } else {
    message.error(data.message || '登录失败')
  }
}
</script>
