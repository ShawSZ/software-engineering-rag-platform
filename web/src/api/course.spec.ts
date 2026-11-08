import { AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { createApiClient } from '@/utils/http-client'
vi.mock('@/utils/request', () => ({ courseHttp: undefined }))
import { createCourseApi, downloadFilename } from './course'

function fixture() {
  const http = createApiClient({ getToken: () => 'fixture-token' })
  const configs: InternalAxiosRequestConfig[] = []
  const adapter: AxiosAdapter = async config => {
    configs.push(config)
    return {
      data: {
        code: 0,
        message: 'success',
        requestId: 'fixture-id',
        data: { items: [], total: 0, page: 1, pageSize: 10 }
      },
      headers: new AxiosHeaders(),
      status: 200,
      statusText: 'ok',
      config
    }
  }
  http.defaults.adapter = adapter
  return { http, configs, api: createCourseApi(http) }
}
describe('COURSE-API-1.0 requests', () => {
  it('generates outlines and exercises with only server-controlled selection fields', async () => {
    const { api, configs } = fixture()
    await api.study.generate(9, 'OUTLINE', 5)
    await api.study.generate(9, 'EXERCISES', 2)
    expect(JSON.parse(configs[0].data)).toEqual({ documentId: 9, kind: 'OUTLINE' })
    expect(JSON.parse(configs[1].data)).toEqual({
      documentId: 9,
      kind: 'EXERCISES',
      questionCount: 2
    })
    expect(configs[0].timeout).toBe(40_000)
  })
  it('sends only a parent ID for follow-ups and restores owned conversation records', async () => {
    const { api, configs } = fixture()
    await api.qa.ask('它有什么作用？', 42)
    expect(JSON.parse(configs[0].data)).toEqual({ question: '它有什么作用？', parentQaId: 42 })
    await api.qa.conversation(42)
    expect(configs[1].url).toBe('/qa/42/conversation')
  })
  it('posts credentials and registration fields without privilege injection', async () => {
    const { api, configs } = fixture()
    const input = {
      username: 'fixture_user',
      password: ' fixture_only ',
      displayName: 'Fixture',
      role: 'ADMIN',
      userId: 999
    }
    await api.auth.register(input)
    await api.auth.login(input.username, input.password)
    await api.auth.me()
    await api.auth.logout()
    expect(configs.map(value => value.url)).toEqual([
      '/auth/register',
      '/auth/login',
      '/auth/me',
      '/auth/logout'
    ])
    expect(JSON.parse(configs[0]!.data)).toEqual({
      username: input.username,
      password: input.password,
      displayName: input.displayName
    })
    expect(JSON.parse(configs[1]!.data).password).toBe(' fixture_only ')
    expect(configs[3]!.data).toBeUndefined()
  })
  it('keeps the canonical page envelope and only allowed document filters', async () => {
    const { api, configs } = fixture()
    const response = await api.documents.list({ keyword: ' test ', title: '  ', categoryId: 42 })
    expect(response).toEqual({
      code: 0,
      message: 'success',
      requestId: 'fixture-id',
      data: { items: [], total: 0, page: 1, pageSize: 10 }
    })
    expect(configs[0]!.params).toEqual({
      page: 1,
      pageSize: 10,
      categoryId: 42,
      keyword: 'test',
      title: undefined
    })
    expect(response.data).not.toHaveProperty('records')
  })
  it('asks only a question with 40 seconds and default non-QA requests use 60 seconds', async () => {
    const { api, configs } = fixture()
    await api.qa.ask('fixture question')
    await api.qa.history({ status: 'FAILED', page: 2, pageSize: 50 })
    await api.qa.favorites()
    expect(JSON.parse(configs[0]!.data)).toEqual({ question: 'fixture question' })
    expect(configs.map(value => value.timeout)).toEqual([40000, 60000, 60000])
    expect(configs[1]!.params).toEqual({ status: 'FAILED', page: 2, pageSize: 50 })
  })
  it('uploads and replaces with only the allowed FormData fields and no manual boundary', async () => {
    const { api, configs } = fixture()
    const file = new File(['synthetic'], 'fixture.txt', { type: 'text/plain' })
    await api.documents.upload(file, 'Fixture', 42)
    await api.documents.replace(9, file)
    expect([...configs[0]!.data.keys()]).toEqual(['file', 'title', 'categoryId'])
    expect([...configs[1]!.data.keys()]).toEqual(['file'])
    expect(configs.map(value => value.url)).toEqual(['/documents', '/documents/9/file'])
    expect(configs.map(value => value.headers.getContentType())).not.toContain(
      expect.stringContaining('boundary=')
    )
  })
  it('addresses every remaining course capability with the correct method and fields', async () => {
    const { api, configs } = fixture()
    await api.categories.list()
    await api.categories.create('Fixture')
    await api.categories.update(4, 'Changed', 1)
    await api.categories.remove(4)
    await api.documents.adminList({ status: 'OFFLINE' })
    await api.documents.detail(9)
    await api.documents.status(9)
    await api.documents.chunks(9, { chunkIndex: 2 })
    await api.documents.update(9, 'Changed', 4)
    await api.documents.offline(9)
    await api.qa.detail(12)
    await api.qa.source(12, 13)
    await api.qa.favorite(12)
    await api.qa.unfavorite(12)
    await api.qa.feedback(12, 'HELPFUL', 'fixture')
    await api.admin.users({ status: 'DISABLED' })
    await api.admin.setUserStatus(7, 'ENABLED')
    await api.admin.feedback({ rating: 'UNHELPFUL', from: '2026-10-01', to: '2026-10-29' })
    await api.admin.stats({ to: '2026-10-29' })
    expect(configs.map(value => `${value.method} ${value.url}`)).toEqual([
      'get /categories',
      'post /categories',
      'patch /categories/4',
      'delete /categories/4',
      'get /admin/documents',
      'get /documents/9',
      'get /documents/9/status',
      'get /documents/9/chunks',
      'patch /documents/9',
      'delete /documents/9',
      'get /qa/12',
      'get /qa/12/sources/13',
      'put /qa/12/favorite',
      'delete /qa/12/favorite',
      'post /qa/12/feedback',
      'get /admin/users',
      'patch /admin/users/7/status',
      'get /admin/feedback',
      'get /admin/stats'
    ])
    expect(configs[4]!.params.status).toBe('OFFLINE')
    expect(configs[7]!.params).toEqual({ page: 1, pageSize: 10, chunkIndex: 2 })
    expect(JSON.parse(configs[8]!.data)).toEqual({ title: 'Changed', categoryId: 4 })
    expect(JSON.parse(configs[14]!.data)).toEqual({ rating: 'HELPFUL', reason: 'fixture' })
    expect(JSON.parse(configs[16]!.data)).toEqual({ status: 'ENABLED' })
    expect(configs[17]!.params).toMatchObject({
      rating: 'UNHELPFUL',
      from: '2026-10-01',
      to: '2026-10-29'
    })
    expect(configs[18]!.params).toEqual({ from: undefined, to: '2026-10-29' })
  })
  it('returns binary bytes with a safely decoded filename', async () => {
    const { api, http } = fixture()
    const binary = new Blob(['synthetic bytes'], { type: 'application/pdf' })
    http.defaults.adapter = async config => ({
      data: binary,
      status: 200,
      statusText: 'ok',
      config,
      headers: new AxiosHeaders({
        'content-type': 'application/pdf',
        'content-disposition': "attachment; filename*=UTF-8''%E8%AF%BE%E7%A8%8B.pdf"
      })
    })
    expect(await api.documents.download(9)).toEqual({
      blob: binary,
      filename: '课程.pdf',
      contentType: 'application/pdf'
    })
  })
  it('handles quoted, missing, malformed and path-like download names', () => {
    expect(downloadFilename('attachment; filename="part; one.txt"', 'fallback')).toBe(
      'part; one.txt'
    )
    expect(
      downloadFilename("attachment; filename*=UTF-8''%ZZ; filename=plain.txt", 'fallback')
    ).toBe('plain.txt')
    expect(downloadFilename('attachment; filename="../../safe.txt"', 'fallback')).toBe('safe.txt')
    expect(downloadFilename(undefined, 'fallback')).toBe('fallback')
  })
})
