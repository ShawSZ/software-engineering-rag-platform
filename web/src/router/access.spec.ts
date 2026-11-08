import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { installAccessGuard, loginDestination } from './access'
import { courseRoutes } from './course-routes'
import { menusForRole } from './navigation'
import { viewImports } from './prefetch'
import { useAuthStore } from '@/stores/auth'
const me = vi.hoisted(() => vi.fn())
vi.mock('@/api/course', () => ({ courseApi: { auth: { me } } }))
function router() {
  const instance = createRouter({ history: createMemoryHistory(), routes: courseRoutes })
  installAccessGuard(instance)
  return instance
}
beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
})
function signIn(role: 'USER' | 'ADMIN') {
  useAuthStore().applySession({
    token: 'fixture',
    userId: role === 'ADMIN' ? 1 : 2,
    username: 'fixture',
    displayName: 'Fixture',
    role
  })
}
describe('course routes and authorization', () => {
  // The first lazy import also transforms UI modules in a cold test worker.
  it('allows anonymous login/registration and redirects protected routes with a return path', async () => {
    const r = router()
    await r.push('/register')
    expect(r.currentRoute.value.path).toBe('/register')
    await r.push('/admin/users')
    expect(r.currentRoute.value.path).toBe('/login')
    expect(r.currentRoute.value.query.redirect).toBe('/admin/users')
  }, 15_000)
  it('defaults to documents and sends students to a real 403 page', async () => {
    signIn('USER')
    const r = router()
    await r.push('/')
    expect(r.currentRoute.value.path).toBe('/documents')
    for (const path of [
      '/admin/documents',
      '/admin/categories',
      '/admin/users',
      '/admin/feedback',
      '/admin/stats'
    ]) {
      await r.push(path)
      expect(r.currentRoute.value.path).toBe('/403')
    }
  })
  it('allows administrators through common and management routes', async () => {
    signIn('ADMIN')
    const r = router()
    for (const path of ['/documents', '/qa', '/history', '/favorites', '/admin/users']) {
      await r.push(path)
      expect(r.currentRoute.value.path).toBe(path)
    }
  })
  it('awaits verified identity before resolving an admin destination', async () => {
    localStorage.setItem('kb_token', 'fixture')
    let resolve!: (value: unknown) => void
    me.mockReturnValue(
      new Promise(done => {
        resolve = done
      })
    )
    const r = router(),
      navigation = r.push('/admin/users')
    await vi.waitFor(() => expect(me).toHaveBeenCalledTimes(1))
    expect(r.currentRoute.value.path).toBe('/')
    expect(useAuthStore().isAdmin).toBe(false)
    resolve({
      data: {
        userId: 2,
        username: 'fixture',
        displayName: 'Fixture',
        role: 'USER',
        status: 'ENABLED'
      }
    })
    await navigation
    expect(r.currentRoute.value.path).toBe('/403')
  })
  it('removes old destinations and gives unknown URLs a 404 page', async () => {
    signIn('ADMIN')
    const r = router()
    for (const path of [
      '/setup',
      '/login/callback',
      '/kbs',
      '/chat',
      '/writer',
      '/vision',
      '/image-gen',
      '/settings/llm',
      '/api-keys',
      '/audits',
      '/scenarios',
      '/missing'
    ]) {
      await r.push(path)
      expect(r.currentRoute.value.meta.status).toBe(404)
      expect(viewImports[path]).toBeUndefined()
    }
    expect(menusForRole(false).map(item => item.path)).toEqual([
      '/documents',
      '/qa',
      '/study',
      '/history',
      '/favorites'
    ])
    expect(menusForRole(true)).toHaveLength(10)
  })
  it.each([
    'https://example.com',
    '//example.com',
    '/\\example.com',
    '/login',
    '/register',
    '/setup',
    '/missing',
    '/documents\n',
    'javascript:alert(1)'
  ])('rejects unsafe or removed login destination %s', input => {
    expect(loginDestination(router(), input, 'ADMIN')).toBe('/documents')
  })
  it('checks the new role again for a stored admin return URL', () => {
    const r = router()
    expect(loginDestination(r, '/admin/users', 'USER')).toBe('/403')
    expect(loginDestination(r, '/admin/users', 'ADMIN')).toBe('/admin/users')
    expect(loginDestination(r, '/documents?title=需求', 'USER')).toContain('/documents?title=')
    expect(r.resolve('/documents/12').meta.title).toBe('资料详情')
    expect(r.resolve('/history/12').meta.title).toBe('问答详情')
  })
})
