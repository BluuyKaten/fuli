/**
 * 前端 K 线数据处理开销基准。模拟 LightweightChart.loadData 的真实工作量：
 * JSON.parse → 构造 candles/volumes → 12 遍指标计算。
 *
 * 对比两组：
 *   plain    — 裸数组，等价于 ref → shallowRef 之后的行为
 *   reactive — 经 Vue reactive 深度代理，等价于当前 ref<CandleItem[]> 的行为
 *
 * 用法：node docs/perf/bench-frontend.mjs
 */
import { createRequire } from 'node:module'
import { performance } from 'node:perf_hooks'

const require = createRequire('file:///H:/allCode/fuli/frontend/package.json')
const { reactive } = require('@vue/reactivity')

const URL_DAILY = 'http://127.0.0.1:8082/stock/daily?stockCode=000012.SZ'
const URL_SMALL = 'http://127.0.0.1:8082/stock/daily?stockCode=600519.SH'
const RUNS = 3

const median = (xs) => {
  const s = [...xs].sort((a, b) => a - b)
  const m = s.length >> 1
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2
}

function calcMA(data, period) {
  const out = new Array(data.length)
  let sum = 0
  for (let i = 0; i < data.length; i++) {
    sum += data[i].close
    if (i >= period) sum -= data[i - period].close
    out[i] = i >= period - 1 ? { time: data[i].time, value: sum / period } : { time: data[i].time, value: NaN }
  }
  return out
}

function calcEMA(data, period) {
  const k = 2 / (period + 1)
  const out = new Array(data.length)
  let prev = data[0].close
  for (let i = 0; i < data.length; i++) {
    prev = data[i].close * k + prev * (1 - k)
    out[i] = { time: data[i].time, value: prev }
  }
  return out
}

function calcMACD(data) {
  const ema12 = calcEMA(data, 12)
  const ema26 = calcEMA(data, 26)
  const dif = new Array(data.length)
  for (let i = 0; i < data.length; i++) dif[i] = { time: data[i].time, value: ema12[i].value - ema26[i].value }
  const dea = new Array(data.length)
  let prev = dif[0].value
  const k = 2 / 10
  for (let i = 0; i < data.length; i++) {
    prev = dif[i].value * k + prev * (1 - k)
    dea[i] = { time: data[i].time, value: prev }
  }
  const hist = new Array(data.length)
  for (let i = 0; i < data.length; i++) hist[i] = { time: data[i].time, value: (dif[i].value - dea[i].value) * 2 }
  return { dif, dea, hist }
}

function calcBOLL(data, period = 20, mult = 2) {
  const mid = calcMA(data, period)
  const upper = new Array(data.length)
  const lower = new Array(data.length)
  for (let i = 0; i < data.length; i++) {
    if (i < period - 1) {
      upper[i] = { time: data[i].time, value: NaN }
      lower[i] = { time: data[i].time, value: NaN }
      continue
    }
    let sq = 0
    for (let j = i - period + 1; j <= i; j++) sq += (data[j].close - mid[i].value) ** 2
    const sd = Math.sqrt(sq / period)
    upper[i] = { time: data[i].time, value: mid[i].value + mult * sd }
    lower[i] = { time: data[i].time, value: mid[i].value - mult * sd }
  }
  return { mid, upper, lower }
}

function calcKDJ(data, period = 9) {
  const k = new Array(data.length)
  const d = new Array(data.length)
  const j = new Array(data.length)
  let pk = 50
  let pd = 50
  for (let i = 0; i < data.length; i++) {
    const from = Math.max(0, i - period + 1)
    let hi = -Infinity
    let lo = Infinity
    for (let x = from; x <= i; x++) {
      if (data[x].high > hi) hi = data[x].high
      if (data[x].low < lo) lo = data[x].low
    }
    const rsv = hi === lo ? 50 : ((data[i].close - lo) / (hi - lo)) * 100
    pk = (2 / 3) * pk + (1 / 3) * rsv
    pd = (2 / 3) * pd + (1 / 3) * pk
    k[i] = { time: data[i].time, value: pk }
    d[i] = { time: data[i].time, value: pd }
    j[i] = { time: data[i].time, value: 3 * pk - 2 * pd }
  }
  return { k, d, j }
}

function calcRSI(data, period = 14) {
  const out = new Array(data.length)
  let gain = 0
  let loss = 0
  for (let i = 0; i < data.length; i++) {
    if (i === 0) {
      out[i] = { time: data[i].time, value: NaN }
      continue
    }
    const ch = data[i].close - data[i - 1].close
    const g = ch > 0 ? ch : 0
    const l = ch < 0 ? -ch : 0
    if (i <= period) {
      gain += g
      loss += l
      out[i] = { time: data[i].time, value: NaN }
    } else {
      gain = (gain * (period - 1) + g) / period
      loss = (loss * (period - 1) + l) / period
      out[i] = { time: data[i].time, value: loss === 0 ? 100 : 100 - 100 / (1 + gain / loss) }
    }
  }
  return out
}

/** 等价于 useIndicators 里 refresh 一次的完整工作量 */
function computeAll(data) {
  return [
    calcMA(data, 5), calcMA(data, 10), calcMA(data, 20), calcMA(data, 60),
    ...Object.values(calcMACD(data)),
    ...Object.values(calcBOLL(data)),
    ...Object.values(calcKDJ(data)),
    calcRSI(data),
  ]
}

function buildCandles(raw) {
  return raw.map((d) => ({
    time: d.tradeDate,
    open: Number(d.openPrice ?? d.open ?? 0),
    high: Number(d.highPrice ?? d.high ?? 0),
    low: Number(d.lowPrice ?? d.low ?? 0),
    close: Number(d.closePrice ?? d.close ?? 0),
    volume: Number(d.vol ?? d.volume ?? 0),
  }))
}

async function fetchJson(url) {
  const res = await fetch(url)
  return await res.text()
}

async function bench(label, url) {
  const text = await fetchJson(url)
  const raw = JSON.parse(text)
  const n = raw.data.length

  const parseTimes = []
  for (let i = 0; i < RUNS; i++) {
    const t0 = performance.now()
    JSON.parse(text)
    parseTimes.push(performance.now() - t0)
  }

  const plainTimes = []
  const reactiveTimes = []
  let checksum = 0

  for (let i = 0; i < RUNS; i++) {
    const candles = buildCandles(raw.data)

    let t0 = performance.now()
    const a = computeAll(candles)
    plainTimes.push(performance.now() - t0)
    checksum += a.length

    const wrapped = reactive(buildCandles(raw.data))
    t0 = performance.now()
    const b = computeAll(wrapped)
    reactiveTimes.push(performance.now() - t0)
    checksum += b.length
  }

  return {
    label,
    bars: n,
    kb: +(Buffer.byteLength(text) / 1024).toFixed(1),
    parse: +median(parseTimes).toFixed(1),
    plain: +median(plainTimes).toFixed(1),
    reactive: +median(reactiveTimes).toFixed(1),
    checksum,
  }
}

const rows = []
rows.push(await bench('000012.SZ 全历史', URL_DAILY))
rows.push(await bench('600519.SH 全历史', URL_SMALL))

const pad = (s, n) => String(s).padEnd(n)
console.log('\n' + pad('数据集', 22) + pad('根数', 8) + pad('体积', 11) + pad('JSON.parse', 13) + pad('指标(裸)', 12) + '指标(reactive)')
console.log('-'.repeat(88))
for (const r of rows) {
  console.log(
    pad(r.label, 22) + pad(r.bars, 8) + pad(`${r.kb} KB`, 11) +
    pad(`${r.parse} ms`, 13) + pad(`${r.plain} ms`, 12) + `${r.reactive} ms`
  )
}
for (const r of rows) {
  const ratio = r.plain > 0 ? (r.reactive / r.plain).toFixed(1) : 'n/a'
  console.log(`\n${r.label}: reactive 是裸数组的 ${ratio} 倍开销，绝对差值 ${(r.reactive - r.plain).toFixed(1)} ms`)
}
