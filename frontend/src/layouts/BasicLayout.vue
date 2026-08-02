<template>
  <a-layout class="layout">
    <a-layout-header class="header">
      <div class="logo">股票复盘系统</div>
      <a-menu theme="dark" mode="horizontal" :selectedKeys="[route.name as string]" @click="onMenuClick">
        <a-menu-item key="Dashboard">仪表盘</a-menu-item>
        <a-menu-item key="TradeList">交易记录</a-menu-item>
        <a-menu-item key="KlineChart">K线图</a-menu-item>
        <a-menu-item key="StockSync">数据同步</a-menu-item>
        <a-menu-item key="AccountProfile">账户信息</a-menu-item>
      </a-menu>
      <div class="user-info">
        <a-dropdown trigger="['click']">
          <a-avatar style="background-color: #1677ff; cursor: pointer">
            {{ (userStore.nickname || userStore.username)?.[0]?.toUpperCase() }}
          </a-avatar>
          <template #overlay>
            <a-menu>
              <a-menu-item key="profile" @click="router.push({ name: 'AccountProfile' })">
                <UserOutlined /> 账户信息
              </a-menu-item>
              <a-menu-item key="password" @click="showPasswordModal = true">
                <LockOutlined /> 修改密码
              </a-menu-item>
              <a-menu-divider />
              <a-menu-item key="logout" @click="handleLogout">
                <LogoutOutlined /> 退出登录
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </div>

      <!-- 修改密码弹窗 -->
      <a-modal v-model:open="showPasswordModal" title="修改密码" :confirm-loading="passwordLoading" @ok="handleChangePassword">
        <a-form layout="vertical">
          <a-form-item label="原密码">
            <a-input-password v-model:value="passwordForm.oldPassword" placeholder="请输入原密码" />
          </a-form-item>
          <a-form-item label="新密码">
            <a-input-password v-model:value="passwordForm.newPassword" placeholder="请输入新密码（至少6位）" />
          </a-form-item>
          <a-form-item label="确认新密码">
            <a-input-password v-model:value="passwordForm.confirmPassword" placeholder="请再次输入新密码" />
          </a-form-item>
        </a-form>
      </a-modal>
    </a-layout-header>
    <a-layout-content class="content">
      <router-view />
    </a-layout-content>
  </a-layout>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { LockOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons-vue'
import { useUserStore } from '@/stores/user'
import { changePassword } from '@/api/auth'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const showPasswordModal = ref(false)
const passwordLoading = ref(false)
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const onMenuClick = ({ key }: { key: string }) => {
  router.push({ name: key })
}

const handleLogout = () => {
  userStore.clearToken()
  router.push('/login')
}

const handleChangePassword = async () => {
  if (!passwordForm.oldPassword || !passwordForm.newPassword || !passwordForm.confirmPassword) {
    message.warning('请填写完整')
    return
  }
  if (passwordForm.newPassword.length < 6) {
    message.warning('新密码至少6位')
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    message.warning('两次输入的新密码不一致')
    return
  }
  passwordLoading.value = true
  try {
    await changePassword(passwordForm.oldPassword, passwordForm.newPassword)
    message.success('密码修改成功，请重新登录')
    showPasswordModal.value = false
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    setTimeout(() => {
      userStore.clearToken()
      router.push('/login')
    }, 1000)
  } catch (error: any) {
    message.error(error.message || '密码修改失败')
  } finally {
    passwordLoading.value = false
  }
}
</script>

<style scoped>
.layout {
  min-height: 100vh;
}

.header {
  display: flex;
  align-items: center;
  padding: 0 24px;
}

.logo {
  color: #fff;
  font-size: 18px;
  font-weight: bold;
  margin-right: 32px;
}

.user-info {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
  color: #fff;
}

.content {
  padding: 24px;
  background: #f0f2f5;
}
</style>
