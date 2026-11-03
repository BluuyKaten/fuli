import { describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import type { PracticeApi, PracticeView } from '@/api/practice'
import { usePracticeSession } from '../usePracticeSession'
import { practice } from './fixtures'

vi.mock('@/api/practice', () => ({ practiceApi: {} }))
const api = (): PracticeApi => ({
  list: vi.fn(async () => []), get: vi.fn(async () => practice()), create: vi.fn(async () => practice()),
  order: vi.fn(async () => practice()), cancel: vi.fn(async () => practice()),
  advance: vi.fn(async () => practice()), finish: vi.fn(async () => practice()), annotations: vi.fn(async () => practice())
})

describe('versioned session mutations', () => {
  it('suppresses a second click and sends exactly the displayed ID and version', async () => {
    const client = api()
    const session = usePracticeSession(client)
    await session.load('9007199254740999')
    let resolve!: (value: PracticeView) => void
    const action = vi.fn(() => new Promise<PracticeView>(done => { resolve = done }))
    const first = session.mutate(action)
    const second = await session.mutate(action)
    expect(second).toBe(false)
    expect(action).toHaveBeenCalledExactlyOnceWith('9007199254740999', 7)
    expect(session.canMutate.value).toBe(false)
    resolve(practice({ version: 8, currentDate: '2024-01-03' }))
    expect(await first).toBe(true)
    expect(session.view.value?.version).toBe(8)
    expect(session.canMutate.value).toBe(true)
    expect(session.sessions.value[0]).not.toHaveProperty('bars')
  })
  it('reloads a stale 409 and never retries its action automatically', async () => {
    const client = api()
    const session = usePracticeSession(client)
    await session.load('9007199254740999')
    vi.mocked(client.get).mockResolvedValue(practice({ version: 8, revealedSteps: 1 }))
    const action = vi.fn(async () => { throw { response: { status: 409 } } })
    expect(await session.mutate(action)).toBe(false)
    expect(action).toHaveBeenCalledTimes(1)
    expect(client.get).toHaveBeenCalledTimes(2)
    expect(session.view.value?.version).toBe(8)
    expect(session.error.value).toContain('已重新载入')
  })
  it('reads after a lost response, blocking more mutations if that read also fails', async () => {
    const client = api()
    const session = usePracticeSession(client)
    await session.load('9007199254740999')
    vi.mocked(client.get).mockRejectedValue(new Error('offline'))
    const action = vi.fn(async () => { throw new Error('timeout') })
    await session.mutate(action)
    expect(session.needsReload.value).toBe(true)
    expect(session.canMutate.value).toBe(false)
    expect(session.error.value).toContain('重新载入失败')
    expect(await session.mutate(action)).toBe(false)
    expect(action).toHaveBeenCalledTimes(1)
  })
  it('does not expose a different session while recovery is pending and ignores out-of-order reads', async () => {
    const client = api()
    const session = usePracticeSession(client)
    await session.load('9007199254740999')
    let resolveA!: (value: PracticeView) => void
    let resolveB!: (value: PracticeView) => void
    vi.mocked(client.get).mockImplementation(id => new Promise(done => { if (id === 'A') resolveA = done; else resolveB = done }))
    const loadA = session.load('A')
    expect(session.view.value).toBeNull()
    const loadB = session.load('B')
    resolveB(practice({ id: 'B' }))
    await loadB
    resolveA(practice({ id: 'A' }))
    await loadA
    expect(session.view.value?.id).toBe('B')
  })
  it('ignores a late mutation after the component scope is disposed', async () => {
    const client = api()
    const scope = effectScope()
    const session = scope.run(() => usePracticeSession(client))!
    await session.load('9007199254740999')
    let resolve!: (value: PracticeView) => void
    const action = vi.fn(() => new Promise<PracticeView>(done => { resolve = done }))
    const pending = session.mutate(action)
    scope.stop()
    resolve(practice({ version: 8 }))
    expect(await pending).toBe(false)
    expect(session.view.value?.version).toBe(7)
    expect(client.get).toHaveBeenCalledTimes(1)
  })
  it('disables mutation on FINISHED regardless of button state', async () => {
    const client = api()
    vi.mocked(client.get).mockResolvedValue(practice({ status: 'FINISHED' }))
    const session = usePracticeSession(client)
    await session.load('9007199254740999')
    const action = vi.fn()
    expect(await session.mutate(action)).toBe(false)
    expect(action).not.toHaveBeenCalled()
  })
})
