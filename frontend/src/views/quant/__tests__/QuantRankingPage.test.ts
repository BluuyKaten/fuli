import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRenderer, nextTick } from 'vue'
import QuantRankingPage from '../QuantRankingPage.vue'

const apiMocks = vi.hoisted(() => ({
  getQuantSummary: vi.fn(),
  getQuantDaily: vi.fn(),
  getQuantLogs: vi.fn(),
  getQuantNotificationConfig: vi.fn(),
  getQuantAutomationConfig: vi.fn(),
  getQuantAutomationStatus: vi.fn(),
  saveQuantAutomationConfig: vi.fn(),
  collectQuantRanking: vi.fn(),
  importQuantRanking: vi.fn(),
  saveQuantNotificationConfig: vi.fn(),
  sendQuantNotificationTest: vi.fn()
}))

vi.mock('@/api/quantRanking', () => apiMocks)

class TestNode {
  children: TestNode[] = []
  parent: TestNode | null = null
  props: Record<string, unknown> = {}
  text = ''

  constructor(readonly tag: string) {}

  addEventListener() {}
  removeEventListener() {}
  getRootNode(): TestNode { return this.parent?.getRootNode() ?? this }
}

const renderer = createRenderer<TestNode, TestNode>({
  createElement: (tag) => new TestNode(tag),
  createText: (text) => Object.assign(new TestNode('#text'), { text }),
  createComment: (text) => Object.assign(new TestNode('#comment'), { text }),
  setText: (node, text) => { node.text = text },
  setElementText: (node, text) => { node.text = text },
  parentNode: (node) => node.parent,
  nextSibling: (node) => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
  patchProp: (node, key, _previous, value) => { node.props[key] = value },
  insert: (node, parent, anchor) => {
    node.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index < 0) parent.children.push(node)
    else parent.children.splice(index, 0, node)
  },
  remove: (node) => {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
  }
})

const all = (node: TestNode): TestNode[] => [node, ...node.children.flatMap(all)]
const textContent = (node: TestNode): string => [node.text, ...node.children.map(textContent)].join('')

let root: TestNode
let app: ReturnType<typeof renderer.createApp>

const click = async (label: string) => {
  const target = all(root).find((node) => node.tag === 'button' && textContent(node).includes(label))
  if (!target) throw new Error(`未找到按钮：${label}`)
  ;(target.props.onClick as (() => void) | undefined)?.()
  await nextTick()
}

const setTextarea = async (value: string) => {
  const target = all(root).find((node) => node.tag === 'textarea')
  if (!target) throw new Error('未找到粘贴输入框')
  ;(target.props.onInput as ((event: { target: { value: string } }) => void) | undefined)?.({ target: { value } })
  await nextTick()
}

const settle = async () => {
  await new Promise<void>((resolve) => setImmediate(resolve))
  await nextTick()
}

const setSetting = async (label: string, value: string | boolean) => {
  const target = all(root).find((node) => node.tag === 'input' && node.props['aria-label'] === label)
  if (!target) throw new Error(`未找到设置：${label}`)
  ;(target.props['onUpdate:modelValue'] as (value: string | boolean) => void)(value)
  await nextTick()
}

beforeEach(async () => {
  vi.clearAllMocks()
  vi.stubGlobal('Document', class {})
  vi.stubGlobal('ShadowRoot', class {})
  apiMocks.getQuantSummary.mockResolvedValue({ data: {
    rows: [{ rank: 1, code: '603706', name: '东方环宇', appearanceCount: 4, averageRank: 1.5, bestRank: 1, latestRank: 2, lastDate: '2026-11-16' }],
    total: 1
  } })
  apiMocks.getQuantDaily.mockResolvedValue({ data: { date: '2026-11-16', rows: [] } })
  apiMocks.getQuantLogs.mockResolvedValue({ data: [] })
  apiMocks.getQuantNotificationConfig.mockResolvedValue({ data: { enabled: false, webhookUrl: '' } })
  apiMocks.getQuantAutomationConfig.mockResolvedValue({ data: {
    enabled: false, executablePath: '', scheduleTime: '15:10', startupCatchUp: true
  } })
  apiMocks.getQuantAutomationStatus.mockResolvedValue({ data: {
    supported: true, running: false, message: '等待采集', nextRunAt: '2026-11-17T15:10:00'
  } })
  apiMocks.saveQuantAutomationConfig.mockResolvedValue({ data: {
    enabled: true, executablePath: 'Z:\\dzh2\\dzh.exe', scheduleTime: '15:10', startupCatchUp: false
  } })
  apiMocks.collectQuantRanking.mockResolvedValue({ data: {
    status: 'SUCCESS', tradingDate: '2026-11-16', importedRows: 20, message: '人气排行采集完成'
  } })
  apiMocks.importQuantRanking.mockResolvedValue({ data: { imported: 1, replaced: false, date: '2026-11-16' } })
  apiMocks.saveQuantNotificationConfig.mockResolvedValue({ data: true })
  apiMocks.sendQuantNotificationTest.mockResolvedValue({ data: { sent: true, status: 'SENT', message: '测试消息已发送' } })

  root = new TestNode('root')
  app = renderer.createApp(QuantRankingPage)
  app.mount(root)
  await settle()
})

afterEach(() => {
  app.unmount()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('量化排行页面', () => {
  it('展示页面标签和总表排行数据', () => {
    const content = textContent(root)
    expect(content).toContain('量化排行')
    expect(content).toContain('总表排行')
    expect(content).toContain('每日快照')
    expect(content).toContain('采集日志')
    expect(content).toContain('通知设置')
    expect(content).toContain('603706')
    expect(content).toContain('东方环宇')
  })

  it('缺失关键列时阻止导入并显示可操作提示', async () => {
    await click('通知设置')
    await setTextarea('代码\t名称\n603706\t东方环宇')
    await click('导入排行')

    expect(textContent(root)).toContain('缺少关键列：人气{wyz}')
    expect(apiMocks.importQuantRanking).not.toHaveBeenCalled()
  })

  it('导入成功后提示同日替换状态并刷新总表', async () => {
    await click('通知设置')
    await setTextarea('代码\t名称\t人气{wyz}\n603706\t东方环宇\t12.4')
    await click('导入排行')
    await settle()

    expect(apiMocks.importQuantRanking).toHaveBeenCalledWith(expect.objectContaining({
      text: expect.stringContaining('603706')
    }))
    expect(textContent(root)).toContain('导入成功')
    expect(apiMocks.getQuantSummary).toHaveBeenCalledTimes(2)
  })

  it('每日快照展开股价和自定义指标列', async () => {
    apiMocks.getQuantDaily.mockResolvedValue({ data: {
      date: '2026-11-16',
      rows: [{
        rank: 1,
        code: '603706',
        name: '东方环宇',
        metric: '人气{wyz}',
        metricValue: 12.4,
        rawFields: { 换手率: '0.29%', 自定义指标: '9.8', 涨幅: '3.65%', 最新: '10.78', '人气[wyz]': '12.4' }
      }]
    } })

    await click('每日快照')
    await click('查询快照')

    const content = textContent(root)
    expect(content).toContain('最新')
    expect(content).toContain('涨幅')
    expect(content).toContain('换手率')
    expect(content).toContain('自定义指标')
    expect(content).toContain('10.78')
    expect(content).toContain('9.8')
    const headers = all(root).filter((node) => node.tag === 'th').map(textContent)
    expect(headers.slice(3, 7)).toEqual(['人气指标', '人气值', '最新', '涨幅'])
    expect(headers).not.toContain('人气[wyz]')
  })

  it.each(['人气{wyz}', '人气[wyz]', '人气(wyz)', ' 人气 ｛ WYZ ｝ ', '人气【wyz】', '人气（wyz）'])('识别%s且无需现成排名即可导入', async (column) => {
    await click('通知设置')
    await setTextarea(`代码\t名称\t${column}\n603706\t东方环宇\t95`)
    await click('导入排行')
    await settle()

    expect(apiMocks.importQuantRanking).toHaveBeenCalledWith(expect.objectContaining({
      mapping: expect.objectContaining({ code: '代码', name: '名称', metricValue: column })
    }))
    expect(textContent(root)).toContain('导入成功')
  })

  it('只有其它数值指标时禁止导入', async () => {
    await click('通知设置')
    await setTextarea('排名\t代码\t名称\t指标值\t涨幅\n1\t603706\t东方环宇\t12.4\t3.65%')
    await click('导入排行')

    expect(apiMocks.importQuantRanking).not.toHaveBeenCalled()
    expect(textContent(root)).toContain('缺少关键列：人气{wyz}')
  })

  it.each(['人气{wyz]', '人气（wyz}'])('拒绝括号不配对的人气列%s', async (column) => {
    await click('通知设置')
    await setTextarea(`代码\t名称\t${column}\n603706\t东方环宇\t95`)
    await click('导入排行')
    await settle()

    expect(apiMocks.importQuantRanking).not.toHaveBeenCalled()
    expect(textContent(root)).toContain('缺少关键列：人气{wyz}')
  })

  it.each([
    { rawFields: { '人气[wyz]': '95' }, expected: '95' },
    { rawFields: { 最新: '10.78' }, expected: '—' }
  ])('旧快照的人气值只取明确的人气指标 $expected', async ({ rawFields, expected }) => {
    apiMocks.getQuantDaily.mockResolvedValue({ data: {
      date: '2026-11-16', rows: [{
        rank: 1, code: '603706', name: '东方环宇', metric: '动量', metricValue: 999, rawFields
      }]
    } })
    await click('每日快照')
    await click('查询快照')
    await settle()

    const row = all(root).find((node) => node.tag === 'tr' && textContent(node).includes('603706'))!
    const cells = row.children.filter((node) => node.tag === 'td').map(textContent)
    expect(cells.slice(3, 5)).toEqual(['人气{wyz}', expected])
    expect(textContent(row)).not.toContain('999')
  })

  it('缺少人气列的文件在上传前被阻止', async () => {
    await click('通知设置')
    const input = all(root).find((node) => node.tag === 'input' && node.props.type === 'file')!
    const file = new File(['代码\t名称\t涨幅\n603706\t东方环宇\t3.65%'], '排行.tsv')
    ;(input.props.onChange as (event: unknown) => void)({ target: { files: [file] } })
    await settle()
    await click('导入排行')
    await settle()

    expect(apiMocks.importQuantRanking).not.toHaveBeenCalled()
    expect(textContent(root)).toContain('缺少关键列：人气{wyz}')
  })

  it('保存定时采集、启动补跑和程序路径并展示运行条件', async () => {
    await click('通知设置')
    await setSetting('启用定时采集', true)
    await setSetting('Java启动后补跑', false)
    await setSetting('大智慧程序路径', ' Z:\\dzh2\\dzh.exe ')
    await click('保存采集设置')
    await settle()

    expect(apiMocks.saveQuantAutomationConfig).toHaveBeenCalledWith({
      enabled: true, executablePath: 'Z:\\dzh2\\dzh.exe', scheduleTime: '15:10', startupCatchUp: false
    })
    expect(textContent(root)).toContain('采集设置已保存')
    expect(textContent(root)).toContain('15:10')
    expect(textContent(root)).toContain('Windows')
    expect(textContent(root)).toContain('Java')
    expect(textContent(root)).toContain('人气{wyz}')
    expect(textContent(root)).toContain('2026-11-17 15:10:00')
  })

  it('设置保存接口返回空数据时仍应用已保存的规范化路径', async () => {
    apiMocks.saveQuantAutomationConfig.mockResolvedValue({ data: null })
    await click('通知设置')
    await setSetting('大智慧程序路径', ' Z:\\dzh2\\dzh.exe ')
    await click('保存采集设置')
    await settle()

    const input = all(root).find((node) => node.tag === 'input' && node.props['aria-label'] === '大智慧程序路径')! as TestNode & { value: string }
    expect(input.value).toBe('Z:\\dzh2\\dzh.exe')
    expect(textContent(root)).toContain('采集设置已保存')
  })

  it.each([
    { status: 'SKIPPED', message: '通知未启用' },
    { status: 'FAILED', message: '企业微信发送失败' }
  ])('测试通知返回$status时显示失败反馈', async ({ status, message }) => {
    apiMocks.sendQuantNotificationTest.mockResolvedValue({ data: { sent: false, status, message } })
    await click('通知设置')
    await click('测试发送')
    await settle()

    expect(textContent(root)).toContain(message)
    const feedback = all(root).find((node) => node.tag === 'p' && textContent(node) === message)
    expect(feedback?.props.class).toContain('error')
    expect(textContent(root)).not.toContain('测试消息已发送')
  })

  it('测试通知只有明确返回sent为true才显示发送成功', async () => {
    await click('通知设置')
    await click('测试发送')
    await settle()

    const feedback = all(root).find((node) => node.tag === 'p' && textContent(node) === '测试消息已发送')
    expect(feedback).toBeDefined()
    expect(feedback?.props.class).not.toContain('error')
  })

  it.each([null, true, { status: 'SENT', message: '' }])('测试通知缺少有效发送结果时不得误报成功', async (body) => {
    apiMocks.sendQuantNotificationTest.mockResolvedValue({ data: body })
    await click('通知设置')
    await click('测试发送')
    await settle()

    expect(textContent(root)).toContain('测试发送失败')
    expect(textContent(root)).not.toContain('测试消息已发送')
  })

  it('手动采集成功后显示数量并刷新每日、总表和日志', async () => {
    await click('从大智慧采集')
    await settle()

    expect(apiMocks.collectQuantRanking).toHaveBeenCalledOnce()
    expect(textContent(root)).toContain('采集成功')
    expect(textContent(root)).toContain('20')
    expect(apiMocks.getQuantDaily).toHaveBeenCalledTimes(2)
    expect(apiMocks.getQuantSummary).toHaveBeenCalledTimes(2)
    expect(apiMocks.getQuantLogs).toHaveBeenCalledTimes(2)
  })

  it.each(['COLLECT_FAILED', 'IMPORT_FAILED', 'ALREADY_RUNNING', 'SKIPPED_NON_TRADING_DAY'])('采集状态%s不得误报成功', async (status) => {
    apiMocks.collectQuantRanking.mockResolvedValue({ data: {
      status, tradingDate: '2026-11-16', importedRows: 0, message: '当前无法完成人气排行采集'
    } })
    await click('从大智慧采集')
    await settle()

    expect(textContent(root)).toContain('当前无法完成人气排行采集')
    expect(textContent(root)).not.toContain('采集成功')
    expect(apiMocks.getQuantLogs).toHaveBeenCalledTimes(2)
  })

  it('采集进行中禁用重复操作，完成后恢复', async () => {
    let complete!: (value: unknown) => void
    apiMocks.collectQuantRanking.mockReturnValue(new Promise((resolve) => { complete = resolve }))
    await click('从大智慧采集')

    const button = all(root).find((node) => node.tag === 'button' && textContent(node).includes('采集中'))!
    expect(button.props.disabled).toBe(true)
    complete({ data: { status: 'SUCCESS', tradingDate: '2026-11-16', importedRows: 1, message: '' } })
    await settle()
    expect(all(root).find((node) => node.tag === 'button' && textContent(node).includes('从大智慧采集'))?.props.disabled).toBe(false)
  })

  it('轮询自动任务状态并在运行结束时刷新排行', async () => {
    app.unmount()
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    root = new TestNode('root')
    app = renderer.createApp(QuantRankingPage)
    app.mount(root)
    await settle()
    apiMocks.getQuantSummary.mockClear()
    apiMocks.getQuantDaily.mockClear()
    apiMocks.getQuantLogs.mockClear()
    apiMocks.getQuantAutomationStatus.mockResolvedValue({ data: {
      supported: true, running: true, message: '自动采集进行中'
    } })
    await vi.advanceTimersByTimeAsync(5000)
    expect(textContent(root)).toContain('自动采集进行中')
    apiMocks.getQuantAutomationStatus.mockResolvedValue({ data: {
      supported: true, running: false, message: '自动采集完成',
      lastRun: { status: 'SUCCESS', tradingDate: '2026-11-16', importedRows: 20, message: '采集完成' }
    } })
    await vi.advanceTimersByTimeAsync(5000)

    expect(textContent(root)).toContain('自动采集完成')
    expect(apiMocks.getQuantSummary).toHaveBeenCalledOnce()
    expect(apiMocks.getQuantDaily).toHaveBeenCalledOnce()
    expect(apiMocks.getQuantLogs).toHaveBeenCalledOnce()
  })
})
