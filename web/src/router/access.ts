import type { Router } from 'vue-router'
import type { Role } from '@/api/course-types'
import { useAuthStore } from '@/stores/auth'

export function loginDestination(router: Router, input: unknown, role: Role): string {
  if (
    typeof input !== 'string' ||
    !input.startsWith('/') ||
    input.startsWith('//') ||
    input.includes('\\') ||
    Array.from(input).some(char => char.charCodeAt(0) <= 32)
  )
    return '/documents'
  const target = router.resolve(input)
  if (!target.matched.length || target.meta.public || target.meta.status || target.path === '/')
    return '/documents'
  if (target.meta.adminOnly && role !== 'ADMIN') return '/403'
  return target.fullPath
}
export function installAccessGuard(router: Router) {
  router.beforeEach(async to => {
    const auth = useAuthStore()
    if (to.meta.public && (!auth.token || auth.isLoggedIn)) return true
    if (!to.meta.public) auth.pendingPath = to.fullPath
    try {
      if (!(await auth.ensureSession()))
        return to.meta.public
          ? true
          : { path: '/login', query: { redirect: to.fullPath }, replace: true }
    } catch {
      return false
    }
    if (to.meta.adminOnly && !auth.isAdmin) return { path: '/403', replace: true }
    return true
  })
}
