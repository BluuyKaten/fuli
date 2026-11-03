<template>
  <div class="practice-rules">
    <div><strong>已同步行情 · 不补缺失日期</strong><p>A 股日线、未复权，创建时锁定数据。只读取已同步记录，不接最新报价；无记录日会跳过，无法区分停牌与缺失。起点前最多 120 根作为背景，不计收益。</p></div>
    <div><strong>下一根开盘 · 全额尝试成交</strong><p>固定股数，100 股整数倍；仅一笔待成交委托，可替换或撤销。T+1；无成交量或一字线不成交，资金或可卖不足整单拒绝，不部分成交、不追单。</p></div>
    <div><strong>固定训练费用 · 不还原历史</strong><p>双向佣金万三，每笔最低 5 元；卖出印花税万五，金额四舍五入到分。不计其他费用，不模拟分红送转、滑点、排队及涨跌停限制。</p></div>
    <div><strong>截至实际结束日 · 统一评价</strong><p>基准在首个可成交日开盘按同样资金、整手和买入费用买入持有；最大回撤按收盘权益。提前结束即揭晓完整走势，剩余持仓只估值，后续走势不改变已冻结结果。</p></div>
  </div>
</template>
<style scoped>
.practice-rules { display: grid; grid-template-columns: 1fr 1fr; gap: 16px 24px; padding-top: 16px; width: 100%; }
strong { color: var(--term-fg); font-size: 11px; font-weight: 500; }
p { font-size: 10px; line-height: 1.95; color: var(--term-fg-muted); margin: 5px 0 0; }
@media (max-width: 750px) { .practice-rules { grid-template-columns: 1fr; gap: 14px; } }
</style>
