<template>
  <a-card :title="isEdit ? '编辑交易' : '新增交易'">
    <a-form ref="formRef" :model="formState" :rules="rules" layout="vertical" style="max-width: 600px">
      <a-form-item label="股票代码" name="stockCode">
        <a-input v-model:value="formState.stockCode" placeholder="如: 600519" />
      </a-form-item>
      <a-form-item label="股票名称" name="stockName">
        <a-input v-model:value="formState.stockName" placeholder="如: 贵州茅台" />
      </a-form-item>
      <a-form-item label="交易类型" name="tradeType">
        <a-radio-group v-model:value="formState.tradeType">
          <a-radio :value="1">买入</a-radio>
          <a-radio :value="2">卖出</a-radio>
        </a-radio-group>
      </a-form-item>
      <a-form-item label="成交价" name="tradePrice">
        <a-input-number v-model:value="formState.tradePrice" :min="0" :precision="2" style="width: 100%" />
      </a-form-item>
      <a-form-item label="数量" name="tradeQuantity">
        <a-input-number v-model:value="formState.tradeQuantity" :min="1" :step="100" style="width: 100%" />
      </a-form-item>
      <a-form-item label="手续费" name="commission">
        <a-input-number v-model:value="formState.commission" :min="0" :precision="2" style="width: 100%" />
      </a-form-item>
      <a-form-item label="印花税" name="tax">
        <a-input-number v-model:value="formState.tax" :min="0" :precision="2" style="width: 100%" />
      </a-form-item>
      <a-form-item label="交易日期" name="tradeDate">
        <a-date-picker v-model:value="formState.tradeDate" style="width: 100%" />
      </a-form-item>
      <a-form-item label="备注" name="remark">
        <a-textarea v-model:value="formState.remark" :rows="3" />
      </a-form-item>
      <a-form-item>
        <a-space>
          <a-button type="primary" @click="onSubmit">保存</a-button>
          <a-button @click="goBack">返回</a-button>
        </a-space>
      </a-form-item>
    </a-form>
  </a-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import dayjs, { type Dayjs } from 'dayjs'
import { createTrade, type TradeRecord } from '@/api/trade'

const route = useRoute()
const router = useRouter()
const formRef = ref()
const isEdit = ref(false)
const editId = ref<number>()

interface FormState {
  stockCode: string
  stockName: string
  tradeType: number
  tradePrice: number
  tradeQuantity: number
  commission: number
  tax: number
  tradeDate: Dayjs | null
  remark: string
}

const formState = reactive<FormState>({
  stockCode: '',
  stockName: '',
  tradeType: 1,
  tradePrice: 0,
  tradeQuantity: 100,
  commission: 0,
  tax: 0,
  tradeDate: dayjs(),
  remark: ''
})

const rules = {
  stockCode: [{ required: true, message: '请输入股票代码' }],
  stockName: [{ required: true, message: '请输入股票名称' }],
  tradeType: [{ required: true, message: '请选择交易类型' }],
  tradePrice: [{ required: true, message: '请输入成交价' }],
  tradeQuantity: [{ required: true, message: '请输入数量' }],
  tradeDate: [{ required: true, message: '请选择交易日期' }]
}

const onSubmit = async () => {
  await formRef.value.validate()
  const data: Partial<TradeRecord> = {
    ...formState,
    tradeDate: formState.tradeDate?.format('YYYY-MM-DD') || ''
  }
  const res = await createTrade(data)
  if (res.code === 200) {
    message.success('保存成功')
    goBack()
  }
}

const goBack = () => {
  router.push('/trade')
}

onMounted(() => {
  if (route.params.id) {
    isEdit.value = true
    editId.value = Number(route.params.id)
  }
})
</script>
