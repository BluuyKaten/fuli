import { computed, getCurrentScope, onScopeDispose, ref, shallowRef } from 'vue'
import { practiceApi, type CreatePracticeRequest, type PracticeApi, type PracticeSummary, type PracticeView } from '@/api/practice'
import { errorText, isStale } from './practiceData'

export function usePracticeSession(api: PracticeApi = practiceApi) {
  const sessions = shallowRef<PracticeSummary[]>([])
  const view = shallowRef<PracticeView | null>(null)
  const listLoading = ref(false)
  const loading = ref(false)
  const busy = ref(false)
  const error = ref('')
  const listError = ref('')
  const needsReload = ref(false)
  const canMutate = computed(() => !!view.value && view.value.status === 'ACTIVE' && !busy.value && !loading.value && !needsReload.value)
  let loadSequence = 0
  let disposed = false
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; loadSequence++ })

  const remember = (value: PracticeView) => {
    if (disposed) return
    view.value = value
    // Summaries never retain bars, annotations, or a second session snapshot.
    const summary: PracticeSummary = {
      id: value.id, stockCode: value.stockCode, startDate: value.startDate, endDate: value.endDate,
      currentDate: value.currentDate, status: value.status, version: value.version,
      equity: value.equity, returnRate: value.returnRate, createdAt: value.createdAt
    }
    sessions.value = [summary, ...sessions.value.filter(session => session.id !== value.id)]
    needsReload.value = false
  }
  const reloadList = async () => {
    if (disposed || listLoading.value) return
    listLoading.value = true
    listError.value = ''
    try {
      const result = await api.list()
      if (!disposed) sessions.value = result
    } catch (cause) { if (!disposed) listError.value = errorText(cause) }
    finally { if (!disposed) listLoading.value = false }
  }
  const load = async (id: string) => {
    if (disposed || busy.value) return
    const sequence = ++loadSequence
    view.value = null
    loading.value = true
    error.value = ''
    needsReload.value = false
    try {
      const result = await api.get(id)
      if (sequence === loadSequence) remember(result)
    } catch (cause) {
      if (sequence === loadSequence) { error.value = errorText(cause); needsReload.value = true }
    } finally { if (sequence === loadSequence) loading.value = false }
  }
  const create = async (data: CreatePracticeRequest) => {
    if (disposed || busy.value || loading.value) return null
    busy.value = true
    error.value = ''
    try {
      const result = await api.create(data)
      if (disposed) return null
      remember(result)
      return result
    } catch (cause) { if (!disposed) error.value = errorText(cause); return null }
    finally { if (!disposed) busy.value = false }
  }
  const mutate = async (action: (id: string, expectedVersion: number) => Promise<PracticeView>) => {
    if (disposed || !canMutate.value || !view.value) return false
    const { id, version } = view.value
    busy.value = true
    error.value = ''
    try {
      const result = await action(id, version)
      if (disposed) return false
      remember(result)
      return true
    } catch (cause) {
      if (disposed) return false
      needsReload.value = true
      const conflict = isStale(cause)
      // A timed-out request may already have committed. Read its outcome; never replay it.
      try {
        const result = await api.get(id)
        if (disposed) return false
        remember(result)
        error.value = conflict
          ? '练习进度已变化，已重新载入。请查看最新状态后再操作。'
          : `${errorText(cause)}。已重新载入当前进度，请确认操作结果。`
      } catch (reloadCause) {
        if (!disposed) error.value = `${errorText(cause)}；重新载入失败：${errorText(reloadCause)}。请先重新载入再操作。`
      }
      return false
    } finally { if (!disposed) busy.value = false }
  }
  return { sessions, view, listLoading, loading, busy, error, listError, needsReload, canMutate, reloadList, load, create, mutate }
}
