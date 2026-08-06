<template>
  <div class="sync-progress">
    <!-- 同步配置 -->
    <div class="sync-config" v-if="!taskId">
      <h3>同步股票日线数据</h3>
      <p class="tip">
        Tushare 基础积分限制: 500 次/分钟, 6000 条/次。每次同步不超过 400 天。
      </p>
      <div class="form-row">
        <label>起始日期:</label>
        <input type="date" v-model="startDate" />
      </div>
      <div class="form-row">
        <label>结束日期:</label>
        <input type="date" v-model="endDate" />
      </div>
      <button @click="startSync" :disabled="isLoading">
        {{ isLoading ? '提交中...' : '开始同步' }}
      </button>
      <p v-if="error" class="error">{{ error }}</p>
    </div>

    <!-- 进度展示 -->
    <div class="progress-panel" v-else>
      <h3>同步进度 - {{ progress.taskId }}</h3>
      <div class="progress-info">
        <span>状态: {{ statusText }}</span>
        <span>{{ progress.startDate }} ~ {{ progress.endDate }}</span>
      </div>

      <!-- 进度条 -->
      <div class="progress-bar-container">
        <div
          class="progress-bar"
          :class="progress.status.toLowerCase()"
          :style="{ width: progress.percent + '%' }"
        >
          {{ progress.percent }}%
        </div>
      </div>

      <!-- 详细信息 -->
      <div class="progress-detail">
        <div class="detail-row">
          <span>已处理: {{ progress.processedDays }} / {{ progress.totalDays }} 天</span>
          <span>当前: {{ progress.currentDate || '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="success">成功: {{ progress.successDays }} 天</span>
          <span class="skipped">跳过: {{ progress.skippedDays }} 天</span>
          <span class="failed">失败: {{ progress.failedDays }} 天</span>
        </div>
        <div class="detail-row">
          <span>同步数据: {{ progress.totalCount }} 条</span>
        </div>
      </div>

      <!-- 当前消息 -->
      <div class="progress-message">
        {{ progress.message }}
      </div>

      <!-- 失败详情 -->
      <div v-if="progress.status === 'FAILED'" class="error-detail">
        <h4>失败详情:</h4>
        <pre>{{ progress.errorDetail }}</pre>
      </div>

      <!-- 操作按钮 -->
      <div class="actions">
        <button v-if="progress.status === 'COMPLETED' || progress.status === 'FAILED'"
                @click="reset">
          返回
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue';

// 日期格式化: Date -> yyyyMMdd
const formatDate = (date: string) => date.replace(/-/g, '');

// 状态文本
const statusMap: Record<string, string> = {
  PENDING: '等待中',
  RUNNING: '同步中',
  COMPLETED: '已完成',
  FAILED: '失败',
};

const startDate = ref('2025-01-01');
const endDate = ref('2025-03-31');
const isLoading = ref(false);
const error = ref('');
const taskId = ref('');
const progress = ref<any>({});
let eventSource: EventSource | null = null;

const statusText = computed(() => statusMap[progress.value.status] || progress.value.status);

// 开始同步
const startSync = async () => {
  if (!startDate.value || !endDate.value) {
    error.value = '请选择起始日期和结束日期';
    return;
  }
  if (startDate.value > endDate.value) {
    error.value = '起始日期不能晚于结束日期';
    return;
  }

  isLoading.value = true;
  error.value = '';

  try {
    const response = await fetch('/data/tushare/sync/daily-by-date-range', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        startDate: formatDate(startDate.value),
        endDate: formatDate(endDate.value),
      }),
    });

    const result = await response.json();
    if (result.code === 200) {
      taskId.value = result.data;
      // 连接 SSE 监听进度
      connectSSE(taskId.value);
    } else {
      error.value = result.message || '启动同步失败';
    }
  } catch (e: any) {
    error.value = '请求失败: ' + e.message;
  } finally {
    isLoading.value = false;
  }
};

// 连接 SSE
const connectSSE = (id: string) => {
  // 关闭旧连接
  if (eventSource) {
    eventSource.close();
  }

  eventSource = new EventSource(`/data/tushare/sync/progress/${id}`);

  eventSource.addEventListener('progress', (event: MessageEvent) => {
    progress.value = JSON.parse(event.data);
  });

  eventSource.onerror = () => {
    // 连接错误时,尝试轮询
    eventSource?.close();
    startPolling(id);
  };
};

// 轮询方式(SSE 不可用时的备选方案)
const startPolling = (id: string) => {
  const timer = setInterval(async () => {
    try {
      const response = await fetch(`/data/tushare/sync/progress/${id}/status`);
      const result = await response.json();
      if (result.code === 200) {
        progress.value = result.data;
        if (result.data.status === 'COMPLETED' || result.data.status === 'FAILED') {
          clearInterval(timer);
        }
      }
    } catch (e) {
      clearInterval(timer);
    }
  }, 2000);
};

// 重置
const reset = () => {
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
  taskId.value = '';
  progress.value = {};
};

// 组件卸载时关闭连接
onUnmounted(() => {
  if (eventSource) {
    eventSource.close();
  }
});
</script>

<style scoped>
.sync-progress {
  max-width: 600px;
  margin: 20px auto;
  padding: 20px;
  border: 1px solid #ddd;
  border-radius: 8px;
}

.tip {
  color: #666;
  font-size: 14px;
  margin-bottom: 16px;
}

.form-row {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}

.form-row label {
  width: 80px;
}

.form-row input {
  flex: 1;
  padding: 6px 10px;
  border: 1px solid #ccc;
  border-radius: 4px;
}

button {
  padding: 8px 20px;
  background: #1890ff;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

button:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.error {
  color: #f5222d;
  margin-top: 10px;
}

.progress-panel h3 {
  margin-top: 0;
}

.progress-info {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
  color: #666;
}

.progress-bar-container {
  width: 100%;
  height: 28px;
  background: #f0f0f0;
  border-radius: 14px;
  overflow: hidden;
  margin-bottom: 16px;
}

.progress-bar {
  height: 100%;
  background: #1890ff;
  color: white;
  text-align: center;
  line-height: 28px;
  font-size: 14px;
  transition: width 0.3s ease;
}

.progress-bar.completed {
  background: #52c41a;
}

.progress-bar.failed {
  background: #f5222d;
}

.progress-bar.running {
  background: #1890ff;
  animation: pulse 1.5s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.progress-detail {
  background: #f9f9f9;
  padding: 12px;
  border-radius: 4px;
  margin-bottom: 12px;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}

.detail-row:last-child {
  margin-bottom: 0;
}

.success { color: #52c41a; }
.skipped { color: #faad14; }
.failed { color: #f5222d; }

.progress-message {
  padding: 10px;
  background: #e6f7ff;
  border-radius: 4px;
  color: #1890ff;
  margin-bottom: 12px;
}

.error-detail {
  background: #fff1f0;
  padding: 12px;
  border-radius: 4px;
  margin-bottom: 12px;
}

.error-detail pre {
  white-space: pre-wrap;
  word-break: break-all;
  color: #f5222d;
  font-size: 12px;
}

.actions {
  text-align: center;
}
</style>
