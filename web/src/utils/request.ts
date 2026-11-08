import { createApiClient } from './http-client'
import { getToken, clearAuth } from './auth'
import { ElMessage } from 'element-plus'
import { getActivePinia } from 'pinia'
import { queryClient } from '@/lib/query-client'

const options = {
  getToken,
  async onSessionExpired(expectedToken: string) {
    if (getToken() !== expectedToken) return
    const pinia = getActivePinia()
    let initializing = false
    if (pinia) {
      const { useAuthStore } = await import('@/stores/auth')
      if (getToken() !== expectedToken) return
      const auth = useAuthStore(pinia)
      initializing = auth.isInitializing
      auth.clearSession('expired')
    } else clearAuth()
    queryClient.clear()
    const { default: router } = await import('@/router')
    if (!initializing && router.currentRoute.value.path !== '/login')
      await router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  },
  onError(error: import('./http-client').ApiError) {
    ElMessage.error(
      error.status && error.status >= 500 && error.requestId
        ? `${error.message}（请求编号：${error.requestId}）`
        : error.message
    )
  }
}
export const courseHttp = createApiClient(options)

// Temporary envelope-returning adapter for upstream pages awaiting F02–F06 migration.
const request = createApiClient(options)
request.interceptors.response.use(response => response.data)
export default request
