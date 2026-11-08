import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import { useDraftStore } from './drafts'
import { queryClient } from '@/lib/query-client'
import { ApiError } from '@/utils/http-client'
import type { ApiResponse, LoginResult, User } from '@/api/course-types'
const api = vi.hoisted(() => ({ me: vi.fn(), login: vi.fn(), logout: vi.fn() }))
vi.mock('@/api/course', () => ({ courseApi: { auth: api } }))
const student: User = {
  userId: 31,
  username: 'student_a',
  displayName: '学生甲',
  role: 'USER',
  status: 'ENABLED',
  createdAt: '2026-10-29T12:00:00+08:00'
}
const session: LoginResult = {
  token: 'synthetic-a',
  userId: 31,
  username: 'student_a',
  displayName: '学生甲',
  role: 'USER'
}
function ok<T>(data: T): ApiResponse<T> {
  return { code: 0, message: 'success', requestId: 'test', data }
}
beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.resetAllMocks()
  api.me.mockResolvedValue(ok(student))
  queryClient.clear()
})
afterEach(() => {
  setActivePinia(undefined)
  queryClient.clear()
})
describe('course account sessions', () => {
  it('ignores persisted roles until one coalesced /me verifies identity', async () => {
    localStorage.setItem('kb_token', 'synthetic-a')
    localStorage.setItem('kb_user', JSON.stringify({ userId: 31, role: 'ADMIN' }))
    let resolve!: (value: ApiResponse<User>) => void
    api.me.mockReturnValue(
      new Promise<ApiResponse<User>>(done => {
        resolve = done
      })
    )
    const auth = useAuthStore()
    expect(auth.user).toBeNull()
    expect(auth.isAdmin).toBe(false)
    const a = auth.ensureSession(),
      b = auth.ensureSession()
    expect(auth.isInitializing).toBe(true)
    expect(auth.isLoggedIn).toBe(false)
    expect(api.me).toHaveBeenCalledTimes(1)
    resolve(ok(student))
    expect(await Promise.all([a, b])).toEqual([true, true])
    expect(auth.user).toMatchObject({ role: 'USER', userId: 31 })
  })
  it('keeps identity hidden on a network failure and permits explicit retry', async () => {
    localStorage.setItem('kb_token', 'synthetic-a')
    api.me
      .mockRejectedValueOnce(new ApiError('网络连接失败', null, null, null, 'network'))
      .mockResolvedValueOnce(ok(student))
    const auth = useAuthStore()
    await expect(auth.ensureSession()).rejects.toThrow('网络连接失败')
    expect(auth.isLoggedIn).toBe(false)
    expect(auth.user).toBeNull()
    expect(auth.token).toBe('synthetic-a')
    expect(auth.restoreError).toBe('网络连接失败')
    expect(await auth.ensureSession()).toBe(true)
    expect(auth.restoreError).toBe('')
  })
  it.each([40101, 40103])(
    'expires %s without retaining private cache or sensitive fields',
    async code => {
      localStorage.setItem('kb_token', 'synthetic-a')
      api.me.mockRejectedValue(new ApiError('会话失效', 401, code, 'test', 'http'))
      const auth = useAuthStore()
      queryClient.setQueryData(['course', 31, 'history'], ['private'])
      expect(await auth.ensureSession()).toBe(false)
      expect(auth.token).toBeNull()
      expect(auth.user).toBeNull()
      expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
      expect(localStorage.getItem('kb_token')).toBeNull()
    }
  )
  it('restores only the same user’s allowed draft after expiration', () => {
    const auth = useAuthStore(),
      drafts = useDraftStore()
    auth.applySession(session)
    drafts.question = '尚未提交的问题'
    drafts.documents.title = '需求'
    drafts.documents.keyword = '功能'
    drafts.documents.categoryId = 7
    Object.assign(drafts, {
      password: 'do-not-store',
      reason: 'do-not-store',
      file: new File(['x'], 'test.txt')
    })
    auth.clearSession('expired')
    expect(drafts.question).toBe('')
    expect(drafts.owner).toBeNull()
    const raw = sessionStorage.getItem('course:draft:31')!
    expect(raw).not.toContain('do-not-store')
    expect(JSON.parse(raw)).toEqual({
      question: '尚未提交的问题',
      documents: { title: '需求', keyword: '功能', categoryId: 7 }
    })
    auth.applySession(session)
    expect(drafts.question).toBe('尚未提交的问题')
    expect(drafts.documents.categoryId).toBe(7)
  })
  it('clears every old account cache and draft on account switch', () => {
    const auth = useAuthStore(),
      drafts = useDraftStore()
    auth.applySession(session)
    drafts.question = 'private question'
    queryClient.setQueryData(['course', 31, 'favorites'], ['private'])
    const revision = auth.sessionRevision
    auth.applySession({ ...session, token: 'synthetic-b', userId: 32, username: 'student_b' })
    expect(auth.sessionRevision).toBeGreaterThan(revision)
    expect(drafts.question).toBe('')
    expect(sessionStorage.getItem('course:draft:31')).toBeNull()
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
  })
  it('clears drafts and tokens even when the explicit logout request fails', async () => {
    const auth = useAuthStore(),
      drafts = useDraftStore()
    auth.applySession(session)
    drafts.question = 'private question'
    api.logout.mockRejectedValue(new Error('offline'))
    await expect(auth.logout()).rejects.toThrow('offline')
    expect(auth.isLoggedIn).toBe(false)
    expect(drafts.question).toBe('')
    expect(sessionStorage.length).toBe(0)
    expect(localStorage.getItem('kb_user')).toBeNull()
  })
  it('never replaces a newer login with a delayed /me response', async () => {
    localStorage.setItem('kb_token', 'synthetic-a')
    let resolve!: (value: ApiResponse<User>) => void
    api.me.mockReturnValue(
      new Promise<ApiResponse<User>>(done => {
        resolve = done
      })
    )
    const auth = useAuthStore()
    const old = auth.ensureSession()
    auth.applySession({ ...session, token: 'synthetic-b', userId: 32, username: 'student_b' })
    resolve(ok(student))
    expect(await old).toBe(false)
    expect(auth.user?.userId).toBe(32)
    expect(auth.token).toBe('synthetic-b')
  })
  it('coalesces login submissions and never persists the password or token inside user metadata', async () => {
    api.login.mockResolvedValue(ok(session))
    const auth = useAuthStore()
    await Promise.all([
      auth.login('student_a', '  unchanged  '),
      auth.login('student_a', '  unchanged  ')
    ])
    expect(api.login).toHaveBeenCalledExactlyOnceWith('student_a', '  unchanged  ')
    expect(JSON.parse(localStorage.getItem('kb_user')!)).toEqual({
      userId: 31,
      username: 'student_a',
      displayName: '学生甲',
      role: 'USER'
    })
    expect(JSON.stringify(localStorage)).not.toContain('unchanged')
    expect(JSON.stringify(sessionStorage)).not.toContain('unchanged')
  })
  it('survives malformed legacy persisted user JSON', () => {
    localStorage.setItem('kb_user', '{invalid')
    expect(useAuthStore().user).toBeNull()
  })
  it('hides the old identity and clears this tab’s drafts when another tab changes accounts', async () => {
    const auth = useAuthStore(),
      drafts = useDraftStore()
    auth.applySession(session)
    drafts.question = 'old private draft'
    queryClient.setQueryData(['course', 31, 'history'], ['private'])
    localStorage.setItem('kb_token', 'synthetic-b')
    expect(auth.syncStoredSession()).toBe(true)
    expect(auth.user).toBeNull()
    expect(auth.isLoggedIn).toBe(false)
    expect(drafts.question).toBe('')
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
    api.me.mockResolvedValue(ok({ ...student, userId: 32, username: 'student_b' }))
    await auth.ensureSession()
    expect(auth.user?.userId).toBe(32)
    expect(auth.syncStoredSession()).toBe(false)
  })
})
