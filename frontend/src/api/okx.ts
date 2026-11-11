import request from '@/utils/request'

/**
 * OKX 账户与余额（ADR-0010）。
 *
 * 注意：后端**永远不返回** secretKey / passphrase，只有 `apiKeyMasked`。
 * 前端也不要尝试缓存明文——提交成功后立刻清空表单。
 */

export interface OkxAccountView {
  label: string
  apiKeyMasked: string
  enabled: boolean
  keyVersion: number
  createTime?: string
  updateTime?: string
}

export interface OkxStatus {
  /** 全局总闸 okx.trade-enabled */
  enabled: boolean
  /** 恒为 true：系统只接模拟盘 */
  simulated: boolean
  /** 当前用户是否已录 Key */
  credentialConfigured: boolean
  principal?: string
  accountUserId?: number | null
  credentialSource?: string
  symbols?: string[]
  supportedPeriods?: string[]
}

export interface OkxAccountPayload {
  label: string
  apiKey: string
  secretKey: string
  passphrase: string
  enabled?: boolean
}

export interface OkxSnapshot {
  ccy: string
  /** 后端列名是 eq，JSON 字段名是 equity（eq 是 OGNL 关键字，不能做 Java 字段名） */
  equity?: number
  cashBal?: number
  availBal?: number
  frozenBal?: number
  upl?: number
  uTime: number
  snapshotAt?: string
}

export const getOkxStatus = () =>
  request.get<any, { code: number; data: OkxStatus }>('/data/okx/status')

export const listOkxAccounts = () =>
  request.get<any, { code: number; data: OkxAccountView[] }>('/data/okx/account')

export const saveOkxAccount = (payload: OkxAccountPayload) =>
  request.post<any, { code: number; data: OkxAccountView }>('/data/okx/account', payload)

export const getLatestSnapshots = () =>
  request.get<any, { code: number; data: OkxSnapshot[] }>('/data/okx/snapshots/latest')

export const getSnapshotHistory = (ccy = 'TOTAL', limit = 60) =>
  request.get<any, { code: number; data: OkxSnapshot[] }>('/data/okx/snapshots', {
    params: { ccy, limit }
  })

export interface OkxWsStatus {
  enabled: boolean
  status: string
  url: string
  subscriptions: string[]
  droppedFrames: number
  lastFrameAt?: string | null
  lastFrameAgeSeconds?: number | null
}

export const getOkxWsStatus = () =>
  request.get<any, { code: number; data: OkxWsStatus }>('/data/okx/ws/status')

/* ---------------- 建议单（阶段 3：人工确认 → 模拟盘下单） ---------------- */

/**
 * 一条下单建议。**它不是订单**：只是 Agent / 策略「想做」什么，
 * 要靠 `status` 与 `source` 一眼看出「建议 ≠ 成交」。
 */
export interface OkxSuggestion {
  id: number
  /** OKX instId，如 BTC-USDT（不带 .CRYPTO 市场后缀） */
  instId: string
  /** buy / sell */
  side: string
  /** limit / market */
  ordType: string
  /** 基础币数量 */
  sz: number
  /** 限价必填；市价单后端会置 null */
  px?: number | null
  tdMode?: string
  reason?: string | null
  /** 0~1；策略信号不传时为 null */
  confidence?: number | null
  /** new（待确认）/ executed（已执行） */
  status: string
  /** agent / strategy ... */
  source?: string | null
  createTime?: string
}

export interface OkxSuggestionExecution {
  suggestionId: number
  /** 固定 sugg-<id>，重复执行由它兜底幂等 */
  clOrdId: string
  ordId?: string | null
  status: string
}

export const listOkxSuggestions = (limit = 20) =>
  request.get<any, { code: number; data: OkxSuggestion[] }>('/data/okx/suggestions', {
    params: { limit }
  })

/**
 * 人工确认一条建议单 → 真实模拟盘下单。
 *
 * 要求用户级交易权限（`sys_user.okx_trade_enabled=1`），服务账号硬 403；
 * 后端会过四道风控，失败时建议单保持 `new`，可以重试。
 */
export const executeOkxSuggestion = (id: number) =>
  request.post<any, { code: number; data: OkxSuggestionExecution }>(
    `/data/okx/suggestions/${id}/execute`
  )

/**
 * 人工否决一条建议单：不产生任何订单，只把状态推进到 `rejected`。
 *
 * 没有这个入口时，不打算执行的建议会永远挂在 `new`，「待确认」数量清不掉。
 */
export const rejectOkxSuggestion = (id: number) =>
  request.post<any, { code: number; data: { suggestionId: number; status: string } }>(
    `/data/okx/suggestions/${id}/reject`
  )
