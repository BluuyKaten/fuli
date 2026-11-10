/**
 * JWT 的「过期」判断（仅用于前端体验，不承担任何安全职责）。
 *
 * <p>服务端仍然独立验签与校验 exp；这里提前判断只是为了在令牌已过期时
 * 直接回登录页并说明原因，而不是让用户点开页面后收到一串 401。
 * 注意：**不校验签名**——前端没有密钥，也不该有。
 */
export function isTokenExpired(token: string | null | undefined): boolean {
  if (!token) return true
  const parts = token.split('.')
  if (parts.length !== 3) return true
  try {
    const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = payload + '='.repeat((4 - (payload.length % 4)) % 4)
    const json = decodeURIComponent(
      atob(padded)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    )
    const exp = JSON.parse(json).exp
    if (typeof exp !== 'number') return false
    // 留 30 秒余量，避免「刚好过期」时卡在边界上
    return exp * 1000 <= Date.now() + 30_000
  } catch {
    // 解析失败大概率是被篡改或格式不对，交给服务端判 401
    return false
  }
}
