import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createApiClient } from '../src/utils/http-client'
import { createCourseApi, courseApi } from '../src/api/course'
import { courseHttp } from '../src/utils/request'
import { useAuthStore } from '../src/stores/auth'
import { useDraftStore } from '../src/stores/drafts'
import { queryClient } from '../src/lib/query-client'
import router from '../src/router'
vi.mock('../src/router', () => ({
  default: {
    push: vi.fn(),
    currentRoute: { value: { path: '/documents', fullPath: '/documents' } }
  }
}))
function createTestStorage(): Storage {
  const values = new Map<string, string>()
  return {
    get length() {
      return values.size
    },
    clear: () => values.clear(),
    getItem: key => values.get(key) ?? null,
    key: index => Array.from(values.keys())[index] ?? null,
    removeItem: key => {
      values.delete(key)
    },
    setItem: (key, value) => {
      values.set(key, String(value))
    }
  }
}

vi.mock('element-plus', async original => ({
  ...(await original<typeof import('element-plus')>()),
  ElMessage: { error: vi.fn() }
}))
const base = process.env.F02_BACKEND_URL
const password = process.env.F02_ADMIN_PASSWORD
if (!base || !password || !/^http:\/\/127\.0\.0\.1:\d+\/api$/.test(base))
  throw new Error('Start an explicitly isolated F02 backend')
afterEach(() => {
  vi.restoreAllMocks()
  globalThis.localStorage?.clear()
  globalThis.sessionStorage?.clear()
  queryClient.clear()
  setActivePinia(undefined)
  vi.unstubAllGlobals()
})
describe('F02 real backend session lifecycle', () => {
  it('registers students, rejects bad login/duplicates, restores identity, handles account disabling and invalidates logout tokens', async () => {
    vi.stubGlobal('localStorage', createTestStorage())
    vi.stubGlobal('sessionStorage', createTestStorage())
    setActivePinia(createPinia())
    const client = createApiClient({ getToken: () => adminToken })
    client.defaults.baseURL = base
    client.defaults.adapter = 'fetch'
    const admin = createCourseApi(client)
    let adminToken: string | null = null
    adminToken = (await admin.auth.login('accept_admin', password)).data.token
    courseHttp.defaults.baseURL = base
    courseHttp.defaults.adapter = 'fetch'
    const created = await courseApi.auth.register({
      username: 'f02_student',
      password: 'F02_fixture_42',
      displayName: 'F02 Student'
    })
    expect(created.data.role).toBe('USER')
    await expect(
      courseApi.auth.register({ username: 'f02_student', password: 'F02_fixture_42' })
    ).rejects.toMatchObject({ status: 409, code: 40901 })
    const auth = useAuthStore()
    await expect(auth.login('f02_student', 'wrong-password')).rejects.toMatchObject({
      status: 401,
      code: 40102
    })
    expect(auth.isLoggedIn).toBe(false)
    await auth.login('f02_student', 'F02_fixture_42')
    expect(auth.user?.role).toBe('USER')
    const originalToken = auth.token!
    const drafts = useDraftStore()
    drafts.question = 'isolated unsent question'
    drafts.documents.title = 'requirements'
    queryClient.setQueryData(['course', created.data.userId, 'history'], ['private'])
    await expect(courseApi.admin.users()).rejects.toMatchObject({ status: 403, code: 40301 })
    expect(auth.isLoggedIn).toBe(true)
    const redirect = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    await admin.admin.setUserStatus(created.data.userId, 'DISABLED')
    const expired = await Promise.allSettled([
      courseApi.auth.me(),
      courseApi.qa.history(),
      courseApi.qa.favorites()
    ])
    expect(expired.every(value => value.status === 'rejected')).toBe(true)
    expect(auth.isLoggedIn).toBe(false)
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
    expect(drafts.question).toBe('')
    expect(redirect).toHaveBeenCalledTimes(1)
    await expect(auth.login('f02_student', 'F02_fixture_42')).rejects.toMatchObject({ code: 40102 })
    await admin.admin.setUserStatus(created.data.userId, 'ENABLED')
    await auth.login('f02_student', 'F02_fixture_42')
    expect(drafts.question).toBe('isolated unsent question')
    // Emulate a page reload with a fresh Pinia: only /me can restore identity.
    setActivePinia(createPinia())
    const restored = useAuthStore()
    expect(restored.user).toBeNull()
    expect(restored.isLoggedIn).toBe(false)
    expect(await restored.ensureSession()).toBe(true)
    expect(restored.user?.userId).toBe(created.data.userId)
    expect(useDraftStore().question).toBe('isolated unsent question')
    const logoutToken = restored.token!
    await restored.logout()
    expect(restored.token).toBeNull()
    expect(sessionStorage.length).toBe(0)
    const oldClient = createApiClient({ getToken: () => logoutToken })
    oldClient.defaults.baseURL = base
    oldClient.defaults.adapter = 'fetch'
    await expect(createCourseApi(oldClient).auth.me()).rejects.toMatchObject({
      status: 401,
      code: 40101
    })
    expect(originalToken).not.toBe(logoutToken)
    const storage = JSON.stringify(localStorage) + JSON.stringify(sessionStorage)
    expect(storage).not.toContain('F02_fixture_42')
  })
})
