<template>
  <div class="account-container">
    <a-card title="账户信息">
      <a-row :gutter="24">
        <a-col :span="8">
          <a-card title="基本信息" :bordered="false">
            <a-form :model="profile" layout="vertical">
              <a-form-item label="用户名">
                <a-input v-model:value="profile.username" disabled />
              </a-form-item>
              <a-form-item label="昵称">
                <a-input v-model:value="profile.nickname" />
              </a-form-item>
              <a-form-item label="邮箱">
                <a-input v-model:value="profile.email" />
              </a-form-item>
              <a-form-item label="手机号">
                <a-input v-model:value="profile.phone" />
              </a-form-item>
              <a-form-item label="注册时间">
                <a-input :value="profile.createTime" disabled />
              </a-form-item>
              <a-form-item>
                <a-button type="primary" @click="handleUpdate" :loading="loading">保存修改</a-button>
              </a-form-item>
            </a-form>
          </a-card>
        </a-col>
        <a-col :span="16">
          <a-card title="资产概览" :bordered="false">
            <a-row :gutter="16">
              <a-col :span="12">
                <a-statistic title="可用现金" :value="profile.cash || 0" :precision="2" prefix="¥" />
              </a-col>
              <a-col :span="12">
                <a-statistic title="初始资金" :value="200000" :precision="2" prefix="¥" />
              </a-col>
            </a-row>
            <a-row :gutter="16" style="margin-top: 16px">
              <a-col :span="12">
                <a-statistic title="累计投入" :value="totalInvestment" :precision="2" prefix="¥" />
              </a-col>
              <a-col :span="12">
                <a-statistic title="累计盈亏" :value="totalProfitLoss" :precision="2" prefix="¥"
                  :value-style="{ color: totalProfitLoss >= 0 ? '#cf1322' : '#3f8600' }" />
              </a-col>
            </a-row>
          </a-card>
        </a-col>
      </a-row>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { getProfile, updateProfile } from '@/api/auth'

const profile = reactive({
  username: '',
  nickname: '',
  email: '',
  phone: '',
  cash: 0,
  createTime: ''
})

const loading = ref(false)
const totalInvestment = ref(0)
const totalProfitLoss = ref(0)

const loadProfile = async () => {
  try {
    const res = await getProfile()
    if (res.code === 200 && res.data) {
      Object.assign(profile, res.data)
    }
  } catch {
    message.error('加载账户信息失败')
  }
}

const handleUpdate = async () => {
  loading.value = true
  try {
    const res = await updateProfile({
      nickname: profile.nickname,
      email: profile.email,
      phone: profile.phone
    })
    if (res.code === 200) {
      message.success('更新成功')
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadProfile()
})
</script>

<style scoped>
.account-container {
  max-width: 1200px;
  margin: 0 auto;
}
</style>
