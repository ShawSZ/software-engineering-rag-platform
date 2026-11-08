import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosError, AxiosHeaders, type AxiosAdapter } from 'axios'
import request, { courseHttp } from './request'
import { courseApi } from '@/api/course'
import { useAuthStore } from '@/stores/auth'
import { queryClient } from '@/lib/query-client'
import router from '@/router'

vi.mock('element-plus', async importOriginal => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: { error: vi.fn() }
}))
const body = {
  code: 0,
  message: 'success',
  requestId: 'composition-fixture',
  data: { role: 'USER' }
}
const ok: AxiosAdapter = async config => ({
  data: body,
  config,
  status: 200,
  statusText: 'ok',
  headers: {}
})
const unauthorized: AxiosAdapter = async config => {
  throw new AxiosError('fixture', 'ERR_BAD_REQUEST', config, undefined, {
    data: { code: 40103, message: '测试停用', requestId: 'composition-fixture', data: null },
    config,
    status: 401,
    statusText: 'unauthorized',
    headers: new AxiosHeaders()
  })
}

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  sessionStorage.clear()
  queryClient.clear()
  setActivePinia(undefined)
})
describe('production request composition', () => {
  it('preserves a valid session after a backend 403', async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.applySession({
      token: 'composition-fixture-token',
      userId: 31,
      username: 'fixture_student',
      displayName: 'Fixture',
      role: 'USER'
    })
    courseHttp.defaults.adapter = async config => {
      throw new AxiosError('forbidden', 'ERR_BAD_REQUEST', config, undefined, {
        data: { code: 40301, message: '无权限', requestId: 'fixture', data: null },
        config,
        status: 403,
        statusText: 'forbidden',
        headers: {}
      })
    }
    await expect(courseApi.admin.users()).rejects.toMatchObject({ status: 403, code: 40301 })
    expect(auth.isLoggedIn).toBe(true)
    expect(localStorage.getItem('kb_token')).toBe('composition-fixture-token')
  })
  it('loads the real import graph and keeps standard/course and legacy envelopes consistent', async () => {
    courseHttp.defaults.adapter = ok
    request.defaults.adapter = ok
    expect(await courseApi.auth.me()).toEqual(body)
    expect(await request.get('/fixture')).toEqual(body)
  })
  it('clears actual Pinia/storage/query state and redirects once for concurrent 401 responses', async () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.applySession({
      token: 'composition-fixture-token',
      userId: 31,
      username: 'fixture_student',
      displayName: 'Fixture',
      role: 'USER'
    })
    queryClient.setQueryData(['course', 31, 'private'], { text: 'synthetic private cache' })
    const redirect = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    courseHttp.defaults.adapter = unauthorized
    await Promise.allSettled([
      courseApi.auth.me(),
      courseApi.qa.history(),
      courseApi.qa.favorites()
    ])
    expect(auth.isLoggedIn).toBe(false)
    expect(localStorage.getItem('kb_token')).toBeNull()
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
    expect(redirect).toHaveBeenCalledExactlyOnceWith({
      path: '/login',
      query: { redirect: router.currentRoute.value.fullPath }
    })
  })
})
