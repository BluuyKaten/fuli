// Optional production-bundle smoke check with installed Chrome/Edge and fixed API data.
// Run from frontend after `npm run build`: node tests/practice-browser-smoke.mjs
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { access, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, extname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dist = join(root, 'dist')
const artifacts = join(root, '.browser-smoke')
const sleep = ms => new Promise(done => setTimeout(done, ms))
const allBars = [
  { date: '2026-01-05', open: 10, high: 11, low: 9, close: 10, volume: 10000 },
  { date: '2026-01-06', open: 11, high: 13, low: 10, close: 12, volume: 10000 },
  { date: '2026-01-07', open: 13, high: 14, low: 11, close: 11, volume: 10000 },
  { date: '2026-01-08', open: 10, high: 11, low: 8, close: 9, volume: 10000 }
]
let state = {
  id: 'smoke-session-1', stockCode: '600519.SH', startDate: '2026-01-05', endDate: '2026-01-08',
  currentDate: '2026-01-05', status: 'ACTIVE', version: 0, createdAt: '2026-01-01T00:00:00Z',
  initialCash: 100000, cash: 100000, quantity: 0, availableQuantity: 0, equity: 100000,
  returnRate: 0, benchmarkEquity: 100000, benchmarkReturnRate: 0, excessReturnRate: 0,
  maxDrawdown: 0, fees: 0, backgroundCount: 1, revealedSteps: 0, totalSteps: 3,
  bars: [allBars[0]], pendingOrder: null, orders: [], fills: [],
  equityCurve: [{ date: '2026-01-05', equity: 100000, benchmarkEquity: 100000 }], annotations: []
}
const apiRequests = []
const unexpected = []
const runtimeErrors = []
const summary = () => Object.fromEntries(['id', 'stockCode', 'startDate', 'endDate', 'currentDate', 'status', 'version', 'equity', 'returnRate', 'createdAt'].map(key => [key, state[key]]))
const json = (response, data, status = 200) => {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify({ code: status, data, message: status === 200 ? 'ok' : 'Unexpected smoke request' }))
}
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://localhost')
    if (url.pathname.startsWith('/api/')) {
      apiRequests.push(`${request.method} ${url.pathname}`)
      let raw = ''
      for await (const chunk of request) raw += chunk
      const body = raw ? JSON.parse(raw) : {}
      if (request.method === 'GET' && url.pathname === '/api/trade/practice') return json(response, [summary()])
      if (request.method === 'GET' && url.pathname === `/api/trade/practice/${state.id}`) return json(response, state)
      if (request.method !== 'GET' && url.pathname.startsWith(`/api/trade/practice/${state.id}/`)) {
        if (body.expectedVersion !== state.version) return json(response, null, 409)
        if (url.pathname.endsWith('/orders')) {
          state.pendingOrder = { id: 'order-1', side: body.side, quantity: body.quantity, submittedDate: state.currentDate, status: 'PENDING' }
          state.orders = [state.pendingOrder]
        } else if (url.pathname.endsWith('/advance')) {
          assert.equal(state.currentDate, '2026-01-05')
          state.currentDate = '2026-01-06'; state.bars = allBars.slice(0, 2); state.revealedSteps = 1
          assert.equal(state.pendingOrder?.side, 'BUY'); assert.equal(state.pendingOrder?.quantity, 100)
          state.cash = 98895; state.quantity = 100; state.equity = 100095; state.returnRate = .00095
          state.benchmarkEquity = 108970.30; state.benchmarkReturnRate = .089703; state.excessReturnRate = -.088753; state.fees = 5
          state.orders = [{ ...state.pendingOrder, status: 'FILLED', executedDate: state.currentDate }]
          state.fills = [{ id: 'fill-1', orderId: 'order-1', side: 'BUY', date: state.currentDate, price: 11, quantity: 100, amount: 1100, commission: 5, tax: 0, fee: 5 }]
          state.pendingOrder = null
          state.equityCurve.push({ date: state.currentDate, equity: state.equity, benchmarkEquity: state.benchmarkEquity })
        } else if (url.pathname.endsWith('/annotations')) state.annotations = body.annotations
        else if (url.pathname.endsWith('/finish')) { state.status = 'FINISHED'; state.reviewBars = allBars; state.pendingOrder = null }
        else { unexpected.push(`${request.method} ${url.pathname}`); return json(response, null, 404) }
        state.version++
        return json(response, state)
      }
      unexpected.push(`${request.method} ${url.pathname}`)
      return json(response, null, 404)
    }
    const pathname = decodeURIComponent(url.pathname)
    let target = resolve(dist, `.${pathname}`)
    if (target !== dist && !target.startsWith(`${dist}${sep}`)) { response.writeHead(403); return response.end() }
    if (!extname(target)) target = join(dist, 'index.html')
    const content = await readFile(target)
    const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon' }
    response.writeHead(200, { 'Content-Type': types[extname(target)] ?? 'application/octet-stream' })
    response.end(content)
  } catch (error) {
    if (error.code === 'ENOENT') { response.writeHead(404); response.end('Not found'); return }
    runtimeErrors.push(String(error)); response.writeHead(500); response.end('Smoke fixture failure')
  }
})
await access(join(dist, 'index.html'))
await mkdir(artifacts, { recursive: true })
const browserPaths = [process.env.CHROME_PATH, 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'].filter(Boolean)
let browserPath
for (const candidate of browserPaths) { try { await access(candidate); browserPath = candidate; break } catch { /* Try next installed browser. */ } }
assert.ok(browserPath, 'Set CHROME_PATH to an installed Chrome/Edge executable')
const profile = await mkdtemp(join(tmpdir(), 'fuli-practice-smoke-'))
await new Promise(done => server.listen(0, '127.0.0.1', done))
const origin = `http://127.0.0.1:${server.address().port}`
const browser = spawn(browserPath, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--window-size=1440,1100', 'about:blank'], { stdio: 'ignore' })
let socket
try {
  let debugPort
  for (let attempt = 0; attempt < 150; attempt++) {
    try { debugPort = (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]; break } catch { await sleep(100) }
  }
  assert.ok(debugPort, 'Browser did not expose a debugging port')
  const targets = await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json()
  const page = targets.find(target => target.type === 'page')
  assert.ok(page, 'Browser page target missing')
  socket = new WebSocket(page.webSocketDebuggerUrl)
  await new Promise((done, reject) => { socket.onopen = done; socket.onerror = reject })
  const pending = new Map(); let nextId = 0
  socket.onmessage = event => {
    const message = JSON.parse(event.data)
    if (message.id) {
      const handler = pending.get(message.id)
      pending.delete(message.id)
      if (message.error) handler?.reject(new Error(JSON.stringify(message.error)))
      else handler?.resolve(message.result)
    } else if (message.method === 'Runtime.exceptionThrown') runtimeErrors.push(JSON.stringify(message.params.exceptionDetails))
  }
  const cdp = (method, params = {}) => new Promise((resolveCall, reject) => {
    const id = ++nextId; pending.set(id, { resolve: resolveCall, reject }); socket.send(JSON.stringify({ id, method, params }))
  })
  const evaluate = async expression => {
    const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails))
    return result.result.value
  }
  const wait = async expression => {
    for (let attempt = 0; attempt < 150; attempt++) { if (await evaluate(expression)) return; await sleep(100) }
    const diagnostic = await evaluate("({text:document.body.innerText,buttons:[...document.querySelectorAll('button')].map(button=>({text:button.textContent,disabled:button.disabled})),orderInputs:[...document.querySelectorAll('input')].map(input=>({value:input.value,label:input.getAttribute('aria-label')}))})")
    await writeFile(join(artifacts, 'failure.json'), JSON.stringify({ expression, diagnostic, state, apiRequests, unexpected, runtimeErrors }, null, 2))
    await screenshot('failure.png')
    throw new Error(`Timed out waiting for ${expression}; diagnostics: ${join(artifacts, 'failure.json')}`)
  }
  const clickText = text => evaluate(`(() => { const button = [...document.querySelectorAll('button')].find(el => el.textContent.trim().includes(${JSON.stringify(text)})); if (!button || button.disabled) throw new Error('Missing/enabled button: ' + ${JSON.stringify(text)}); button.click(); return true })()`)
  const screenshot = async name => {
    const image = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
    await writeFile(join(artifacts, name), Buffer.from(image.data, 'base64'))
  }
  await cdp('Runtime.enable'); await cdp('Page.enable')
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: "localStorage.setItem('token','browser-smoke-token');localStorage.setItem('username','测试用户');localStorage.setItem('userId','1')" })
  await cdp('Page.navigate', { url: `${origin}/practice?session=${state.id}` })
  await wait("document.querySelectorAll('.chart-host canvas').length >= 2 && document.querySelector('.decision-clock')?.textContent.includes('2026-01-05')")
  assert.equal(await evaluate("document.querySelector('.ohlc').textContent.includes('2026-01-06')"), false)
  await screenshot('initial.png')
  await clickText('手工买点')
  await evaluate("document.querySelector('.chart-host').scrollIntoView({block:'center'})")
  await sleep(150)
  await wait("document.querySelector('.annotation-layer > g > line') !== null")
  const chartBox = await evaluate("(() => {const box=document.querySelector('.chart-host').getBoundingClientRect(); const x=Number(document.querySelector('.annotation-layer > g > line').getAttribute('x1')); return {x:box.x+x,y:box.y+120}})()")
  await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', ...chartBox })
  await cdp('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, ...chartBox })
  await cdp('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, ...chartBox })
  await wait("document.querySelector('.chart-footnote').textContent.includes('1 / 200')")
  assert.equal(state.annotations.length, 1)
  await clickText('画线')
  assert.equal(await evaluate("[...document.querySelectorAll('.tools button.selected')].some(button => button.textContent === '手工买点')"), false)
  await cdp('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
  await cdp('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
  await wait("[...document.querySelectorAll('.tools button.selected')].some(button => button.textContent === '浏览')")
  await clickText('提交下一日委托')
  await wait("document.querySelector('.pending-order')?.textContent.includes('100 股')")
  assert.equal(state.fills.length, 0)
  await clickText('尝试成交并揭晓下一日')
  await wait("document.querySelector('.decision-clock')?.textContent.includes('2026-01-06') && document.querySelector('.metric.primary')?.textContent.includes('100,095.00')")
  assert.equal(state.fills.length, 1)
  await screenshot('active.png')
  await cdp('Page.reload')
  await wait("document.querySelectorAll('.chart-host canvas').length >= 2 && document.querySelector('.decision-clock')?.textContent.includes('2026-01-06')")
  await clickText('提前结束并揭晓完整走势')
  await wait("document.querySelector('.ant-modal-confirm-btns') || [...document.querySelectorAll('.ant-modal-footer button')].some(button=>button.textContent.includes('结束并进入复盘'))")
  await clickText('结束并进入复盘')
  await wait("document.querySelector('.finished-account') && document.querySelector('.ohlc')?.textContent.includes('2026-01-08')")
  assert.equal(await evaluate("document.querySelector('.decision-clock').textContent.includes('2026-01-06')"), true)
  assert.equal(await evaluate("document.querySelector('.metric.primary').textContent.includes('100,095.00')"), true)
  await screenshot('review-desktop.png')
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  await sleep(300)
  await screenshot('review-mobile.png')
  assert.equal(await evaluate('document.documentElement.scrollWidth > innerWidth + 1'), false, 'Page overflows mobile viewport')
  assert.deepEqual(unexpected, [], 'Practice page requested ordinary account/market APIs')
  assert.deepEqual(runtimeErrors, [], 'Browser runtime exceptions')
  await writeFile(join(artifacts, 'result.json'), JSON.stringify({ status: 'passed', browser: browserPath, apiRequests, unexpected, runtimeErrors, checks: ['real canvas rendering', 'known prefix only', 'manual annotation persistence', 'exclusive tools and Escape', 'order then advance', 'reload restores progress', 'early review freezes indicators', 'mobile viewport'] }, null, 2))
  console.log(`Practice browser smoke passed (${apiRequests.length} API requests). Screenshots: ${artifacts}`)
} finally {
  socket?.close()
  browser.kill()
  await new Promise(done => { if (browser.exitCode !== null) done(); else { browser.once('exit', done); setTimeout(done, 3000).unref() } })
  await new Promise(done => server.close(done))
  // Delete only this run's verified temporary profile, never a user's browser profile.
  const checkedProfile = resolve(profile)
  if (checkedProfile.startsWith(resolve(tmpdir()) + sep + 'fuli-practice-smoke-')) {
    await rm(checkedProfile, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 }).catch(() => {})
  }
}
