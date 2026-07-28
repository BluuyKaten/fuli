<template>
  <a-card title="Dashboard">
    <a-descriptions bordered :column="1">
      <a-descriptions-item label="总资产曲线">由 /api/analysis/stats 返回 assetCurve</a-descriptions-item>
      <a-descriptions-item label="胜率">由 /api/analysis/stats 返回 winRate</a-descriptions-item>
      <a-descriptions-item label="盈亏比">由 /api/analysis/stats 返回 profitLossRatio</a-descriptions-item>
      <a-descriptions-item label="月度柱状图">由 /api/analysis/stats 返回 monthlyBars</a-descriptions-item>
    </a-descriptions>
  </a-card>
</template>
