import { onScopeDispose, ref, shallowRef } from 'vue'
import type { PageResult } from '@/types/api'

export function usePagedList<T>() {
  const records = shallowRef<T[]>([])
  const page = ref(1)
  const size = ref(20)
  const total = ref(0)
  const loading = ref(false)
  const error = ref('')
  let revision = 0
  onScopeDispose(() => {
    revision += 1
  })

  async function load(fetchPage: (page: number, size: number) => Promise<PageResult<T>>) {
    const current = ++revision
    const requestedPage = page.value
    const requestedSize = size.value
    loading.value = true
    error.value = ''
    try {
      let result = await fetchPage(requestedPage, requestedSize)
      if (current !== revision) return false
      let last = Math.max(1, Math.ceil(result.total / requestedSize))
      if (result.total > 0 && requestedPage > last) {
        // 仅回退重查一次；并发删除继续收缩时等待用户刷新，避免空页请求循环。
        result = await fetchPage(last, requestedSize)
        if (current !== revision) return false
        const latestLast = Math.max(1, Math.ceil(result.total / requestedSize))
        if (result.total > 0 && last > latestLast) {
          page.value = latestLast
          total.value = result.total
          records.value = []
          error.value = '列表已发生变化，请刷新当前页'
          return false
        }
        last = latestLast
      }
      total.value = result.total
      page.value = result.total === 0 ? 1 : Math.min(result.page, last)
      records.value = result.records
      return true
    } catch {
      if (current === revision) {
        records.value = []
        error.value = '列表加载失败，请重试'
      }
      return false
    } finally {
      if (current === revision) loading.value = false
    }
  }

  function reset() {
    page.value = 1
  }
  return { records, page, size, total, loading, error, load, reset }
}
