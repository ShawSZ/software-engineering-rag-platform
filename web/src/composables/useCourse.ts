import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import { useAuthStore } from '@/stores/auth'
import { courseQueryKeys } from '@/lib/course-query-keys'
import { ApiError } from '@/utils/http-client'
import { courseApi } from '@/api/course'

export function useCourse() {
  const auth = useAuthStore()
  const uid = computed(() => auth.user?.userId || 0)
  const client = useQueryClient()
  const enabled = computed(() => auth.isLoggedIn)
  const refresh = () => client.invalidateQueries({ queryKey: courseQueryKeys.user(uid.value) })
  return { auth, uid, client, enabled, refresh }
}
export function usePageVisible() {
  const active = ref(true)
  const visible = ref(document.visibilityState !== 'hidden')
  const update = () => {
    visible.value = document.visibilityState !== 'hidden'
  }
  onMounted(() => document.addEventListener('visibilitychange', update))
  onBeforeUnmount(() => document.removeEventListener('visibilitychange', update))
  onActivated(() => {
    active.value = true
    update()
  })
  onDeactivated(() => {
    active.value = false
  })
  return computed(() => active.value && visible.value)
}
export function message(error: unknown): string {
  if (error instanceof ApiError)
    return (
      error.message +
      (error.requestId && error.status && error.status >= 500
        ? `（请求编号：${error.requestId}）`
        : '')
    )
  return error instanceof Error ? error.message : '请求失败，请重试。'
}
export function length(value: string) {
  return Array.from(value).length
}
export function fileError(file: File | null): string {
  if (!file) return '请选择 PDF、TXT、DOCX 或 PPTX 文件'
  if (!/\.(pdf|txt|docx|pptx)$/i.test(file.name)) return '仅支持 PDF、TXT、DOCX 和 PPTX 文件'
  if (!file.size) return '不能上传空文件'
  if (file.size > 20971520) return '单文件不能超过 20 MB'
  return ''
}
export function dateError(from: string, to: string) {
  return from && to && from > to ? '开始日期不能晚于结束日期' : ''
}
export async function downloadDocument(id: number) {
  const result = await courseApi.documents.download(id)
  const url = URL.createObjectURL(result.blob)
  const link = document.createElement('a')
  link.href = url
  link.download = result.filename
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
