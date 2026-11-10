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
