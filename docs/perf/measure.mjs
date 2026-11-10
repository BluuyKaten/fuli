/**
 * K 线接口基线测量。Phase 0 与 Phase 5 使用同一套脚本，保证前后可比。
 *
 * 用法：node docs/perf/measure.mjs [--json]
 *
 * 方法：每个端点先预热 1 次（排除 TCP/TLS 建立与 JIT 预热），再测 5 次，
 * 报告 min / median / max。样本量小，故不报 P99——5 个样本的 P99 等于最大值，
 * 标成 P99 会误导人。需要 P99 时把 RUNS 提到 100。
 */
import { performance } from 'node:perf_hooks'

const RUNS = 5
const WARMUP = 1
const TIMEOUT_MS = 60_000

const ENDPOINTS = [
  {
    id: 'daily-600519',
    label: '日线 600519.SH（1,558 根）',
    url: 'http://127.0.0.1:8082/stock/daily?stockCode=600519.SH',
  },
  {
    id: 'daily-000012',
    label: '日线 000012.SZ（最大 5,071 根）',
    url: 'http://127.0.0.1:8082/stock/daily?stockCode=000012.SZ',
  },
  {
    id: 'minute-1-600519',
    label: '1 分钟线 600519（上限 5,000 根）',
    url: 'http://127.0.0.1:8082/stock/minute?code=600519&period=1',
  },
  {
    id: 'daily-direct-600519',
    label: '日线直连 data-service（排除 Feign）',
    url: 'http://127.0.0.1:8084/data/stock/daily?stockCode=600519.SH',
  },
]

const median = (xs) => {
  const s = [...xs].sort((a, b) => a - b)
  const m = s.length >> 1
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2
}

async function hit(url) {
  const ctl = new AbortController()
  const timer = setTimeout(() => ctl.abort(), TIMEOUT_MS)
  const t0 = performance.now()
  try {
    const res = await fetch(url, { signal: ctl.signal })
    const buf = await res.arrayBuffer()
    return {
      ok: res.ok,
      status: res.status,
      bytes: buf.byteLength,
      ms: performance.now() - t0,
    }
  } finally {
    clearTimeout(timer)
  }
}

const rows = []

for (const ep of ENDPOINTS) {
  process.stdout.write(`测量 ${ep.label} ... `)
  try {
    for (let i = 0; i < WARMUP; i++) await hit(ep.url)

    const samples = []
    for (let i = 0; i < RUNS; i++) samples.push(await hit(ep.url))

    const times = samples.map((s) => s.ms)
    const bytes = samples[0].bytes
    rows.push({
      id: ep.id,
      label: ep.label,
      status: samples[0].status,
      bytes,
      kb: +(bytes / 1024).toFixed(1),
      min: +Math.min(...times).toFixed(1),
      median: +median(times).toFixed(1),
      max: +Math.max(...times).toFixed(1),
    })
    console.log('ok')
  } catch (e) {
    rows.push({ id: ep.id, label: ep.label, error: String(e.message || e) })
    console.log(`失败：${e.message || e}`)
  }
}

const pad = (s, n) => String(s).padEnd(n)
console.log('\n' + pad('端点', 34) + pad('状态', 6) + pad('体积', 11) + pad('min', 9) + pad('median', 9) + 'max')
console.log('-'.repeat(80))
for (const r of rows) {
  if (r.error) {
    console.log(pad(r.label, 34) + 'ERR   ' + r.error)
    continue
  }
  console.log(
    pad(r.label, 34) + pad(r.status, 6) + pad(`${r.kb} KB`, 11) +
    pad(`${r.min} ms`, 9) + pad(`${r.median} ms`, 9) + `${r.max} ms`
  )
}

if (process.argv.includes('--json')) {
  console.log('\n' + JSON.stringify(rows, null, 2))
}
