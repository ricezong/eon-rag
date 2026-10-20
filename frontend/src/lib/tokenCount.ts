import { onScopeDispose, ref, watch } from 'vue'
import { apiPost } from '@/lib/api'
import { estimateTokens } from '@/lib/tokens'

export interface InitialTokenCount {
  text: string
  count: number
}

/**
 * 计算编辑器当前文本的精确 Token 数；网络失败时回退到本地估算。
 * initial 用于复用后端已持久化的精确值，避免打开弹窗时闪烁。
 */
export function useTokenCounts(
  texts: () => string[],
  initial: () => InitialTokenCount[] = () => [],
  enabled: () => boolean = () => true,
) {
  const counts = ref<number[]>([])
  const exact = ref<boolean[]>([])
  const pending = ref(false)
  let timer: ReturnType<typeof setTimeout> | undefined
  let requestId = 0

  const resolve = () => {
    if (!enabled()) {
      counts.value = []
      exact.value = []
      pending.value = false
      requestId += 1
      if (timer) clearTimeout(timer)
      return
    }

    const values = texts()
    const initialCounts = initial()
    const initialMap = new Map(initialCounts.map((item) => [item.text, item.count]))
    const fallback = values.map(estimateTokens)
    counts.value = values.map((value, index) => initialMap.get(value) ?? fallback[index])
    exact.value = values.map((value) => initialMap.has(value))
    const needsRemote = exact.value.some((value) => !value)

    if (timer) clearTimeout(timer)
    if (!needsRemote) {
      pending.value = false
      requestId += 1
      return
    }

    const currentId = ++requestId
    pending.value = true
    timer = setTimeout(async () => {
      try {
        const result = await apiPost<number[]>('/api/tokens/count', { texts: values })
        if (currentId !== requestId) return
        if (Array.isArray(result) && result.length === values.length) {
          counts.value = result
          exact.value = values.map(() => true)
        }
      } catch {
        if (currentId !== requestId) return
        counts.value = values.map((value, index) => initialMap.get(value) ?? fallback[index])
        exact.value = values.map((value) => initialMap.has(value))
      } finally {
        if (currentId === requestId) pending.value = false
      }
    }, 200)
  }

  watch([texts, enabled], resolve, { immediate: true })
  onScopeDispose(() => {
    requestId += 1
    if (timer) clearTimeout(timer)
  })

  return { counts, exact, pending }
}
