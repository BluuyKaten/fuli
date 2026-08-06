/**
 * 后端业务错误码定义(与后端 BusinessException 对齐)
 */
export const BizCode = {
  /** 持仓不足 */
  POSITION_INSUFFICIENT: 4001,
  /** 资金不足 */
  CASH_INSUFFICIENT: 4002,
  /** 价格超涨跌停 */
  PRICE_OUT_OF_LIMIT: 4003,
  /** 缺少行情数据 */
  MISSING_MARKET_DATA: 4004,
  /** 仅允许删除最后一笔交易 */
  NOT_LAST_TRADE: 4005,
  /** 资金校验失败 */
  CASH_VALIDATION_FAILED: 4006,
} as const

/** 错误码 → 中文提示映射(兜底,优先使用后端返回的 message) */
export const BIZ_CODE_MESSAGE: Record<number, string> = {
  [BizCode.POSITION_INSUFFICIENT]: '持仓不足',
  [BizCode.CASH_INSUFFICIENT]: '可用资金不足',
  [BizCode.PRICE_OUT_OF_LIMIT]: '价格超出涨跌停范围',
  [BizCode.MISSING_MARKET_DATA]: '缺少行情数据,请先同步',
  [BizCode.NOT_LAST_TRADE]: '仅允许删除某股票的最后一笔交易',
  [BizCode.CASH_VALIDATION_FAILED]: '资金校验失败,已拒绝交易',
}

/**
 * 业务错误类(携带后端 code 与 message)
 */
export class BizError extends Error {
  code: number
  constructor(code: number, message: string) {
    super(message)
    this.name = 'BizError'
    this.code = code
  }
}
