/**
 * 股票代码格式处理工具
 * 数据库存 Tushare 格式（300750.SZ / 600519.SH），用户输入/显示用纯数字
 */

/** 去掉后缀，返回纯数字代码 */
export const toPureCode = (code: string): string => {
  if (!code) return ''
  return code.split('.')[0].trim()
}

/** 根据纯数字代码推断交易所后缀 */
const inferSuffix = (pureCode: string): string => {
  if (!pureCode) return ''
  // 6 开头 → 上海 (.SH)
  if (pureCode.startsWith('6')) return '.SH'
  // 0/3 开头 → 深圳 (.SZ)
  if (pureCode.startsWith('0') || pureCode.startsWith('3')) return '.SZ'
  // 4/8 开头 → 北京 (.BJ)
  if (pureCode.startsWith('4') || pureCode.startsWith('8')) return '.BJ'
  return ''
}

/** 纯数字 → Tushare 格式 */
export const toTushareCode = (pureCode: string): string => {
  const pure = toPureCode(pureCode)
  // 已有后缀直接返回
  if (pureCode.includes('.')) return pureCode
  return pure + inferSuffix(pure)
}

/** 标准化：统一成纯数字（用于前端显示和匹配） */
export const normalizeCode = (code: string): string => toPureCode(code)

/** 模糊匹配：支持纯数字、带后缀、前缀匹配 */
export const codeMatch = (code: string, keyword: string): boolean => {
  if (!keyword) return true
  const c = toPureCode(code).toLowerCase()
  const k = keyword.toLowerCase().trim()
  if (c.includes(k)) return true
  // 反向：keyword 带后缀但 code 不带
  if (k.includes('.')) return toPureCode(k) === c
  return false
}
