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
            <a-divider />
            <a-button type="primary" danger @click="showResetModal">
              <template #icon><ReloadOutlined /></template>
              重置资金
            </a-button>
          </a-card>
        </a-col>
      </a-row>
    </a-card>

    <a-modal v-model:open="resetModalOpen" title="重置资金" :confirm-loading="resetLoading" @ok="handleReset" @cancel="resetModalOpen = false">
      <a-alert
        type="warning"
        show-icon
        description="重置资金将清空所有交易记录和持仓，恢复为初始状态。此操作不可撤销！"
        style="margin-bottom: 16px"
      />
      <a-form layout="vertical">
        <a-form-item label="新的初始资金">
          <a-input-number v-model:value="newInitialCash" :min="10000" :step="10000" style="width: 100%" prefix="¥" :precision="2" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { getProfile, updateProfile } from '@/api/auth'
import { resetCash, clearAllTrades } from '@/api/account'

const profile = reactive({
  id: 0,
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

const resetModalOpen = ref(false)
const resetLoading = ref(false)
const newInitialCash = ref(200000)

const loadProfile = async () => {
  try {
    const res = await getProfile()
    if (res.code === 200 && res.data) {
      Object.assign(profile, res.data)
      // 计算累计投入（买入总额 - 卖出总额）
      calculateInvestment()
    }
  } catch {
    message.error('加载账户信息失败')
  }
}

const calculateInvestment = async () => {
  try {
    // 从交易记录计算累计投入
    const { getStatistics } = await import('@/api/trade')
    const res = await getStatistics({ userId: profile.id, pageNum: 1, pageSize: 1 })
    if (res.code === 200 && res.data) {
      // 累计投入 = 买入总额 - 卖出总额（净投入）
      totalInvestment.value = Math.max(0, Number(res.data.totalBuyAmount || 0) - Number(res.data.totalSellAmount || 0))
      totalProfitLoss.value = Number(res.data.totalProfitLoss || 0)
    }
  } catch {
    // 计算失败时保持为 0
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

const showResetModal = () => {
  Modal.confirm({
    title: '确认重置',
    content: '确定要重置资金吗？这将清空所有交易记录和持仓！',
    okText: '确认重置',
    okType: 'danger',
    cancelText: '取消',
    onOk() {
      resetModalOpen.value = true
    }
  })
}

const handleReset = async () => {
  if (newInitialCash.value < 10000) {
    message.warning('初始资金不能少于 10,000')
    return
  }
  if (!profile.id) {
    message.error('用户信息加载失败')
    return
  }
  resetLoading.value = true
  try {
    await clearAllTrades(profile.id)
    await resetCash(profile.id, newInitialCash.value)
    message.success('资金重置成功')
    resetModalOpen.value = false
    await loadProfile()
  } catch (error: any) {
    message.error(error.message || '重置失败')
  } finally {
    resetLoading.value = false
  }
}

onMounted(() => {
  loadProfile()
})
</script>

<style scoped>
.account-container {
  width: 100%;
  height: 100%;
  overflow-y: auto;
  padding: 16px;
  box-sizing: border-box;
}
</style>
