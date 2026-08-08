/**
 * 货币计算工具（整数运算，避免 JS 浮点精度问题）
 *
 * 价格/金额以「元」为单位输入，内部转为「分」（整数）计算，输出再转回元。
 * 例如 0.1 + 0.2 用浮点会有精度误差，用整数分计算则精确。
 */

/** 元 → 分（四舍五入到整数分） */
export const yuanToFen = (yuan: number): number => Math.round(yuan * 100)

/** 分 → 元 */
export const fenToYuan = (fen: number): number => fen / 100

/**
 * 计算交易金额（分）：价格(元) × 数量
 * @returns 金额（分，整数）
 */
export const calcTradeAmountFen = (priceYuan: number, quantity: number): number => {
  return yuanToFen(priceYuan) * quantity
}

/**
 * 计算最大可买数量（手，100 的整数股）
 * @param cashYuan 可用资金（元）
 * @param priceYuan 买入价格（元）
 * @returns 最大可买数量（100 的整数倍）
 */
export const calcMaxBuyQuantity = (cashYuan: number, priceYuan: number): number => {
  if (!priceYuan || priceYuan <= 0 || !cashYuan || cashYuan <= 0) return 0
  // 用整数分计算，避免浮点误差
  const maxQty = Math.floor(yuanToFen(cashYuan) / yuanToFen(priceYuan))
  // 向下取整到 100 的整数倍
  return Math.max(0, Math.floor(maxQty / 100) * 100)
}

/** 格式化金额显示（分 → 元，保留 2 位） */
export const formatYuan = (fen: number): string => (fen / 100).toFixed(2)
