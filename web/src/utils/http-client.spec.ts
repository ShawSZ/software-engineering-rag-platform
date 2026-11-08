import { AxiosError, AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, createApiClient, QA_TIMEOUT } from './http-client'

const success = (data: unknown = null) => ({
  code: 0,
  message: 'success',
  requestId: 'fixture-request',
  data
})
const rejected =
  (status: number, code: number, blob = false): AxiosAdapter =>
  async config => {
    const body = { code, message: '可读错误原因', requestId: 'server-error-id', data: null }
    throw new AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, undefined, {
      status,
      statusText: 'error',
      config,
      headers: new AxiosHeaders({ 'content-type': 'application/json' }),
      data: blob ? new Blob([JSON.stringify(body)], { type: 'application/json' }) : body
    })
  }
describe('course HTTP transport', () => {
  it('uses same-origin /api, 60 seconds and the current Bearer on each request', async () => {
    let token: string | null = 'fixture-token-one'
    const client = createApiClient({ getToken: () => token })
    const configs: InternalAxiosRequestConfig[] = []
    client.defaults.adapter = async config => {
      configs.push(config)
      return { data: success(), status: 200, statusText: 'ok', headers: {}, config }
    }
    await client.get('/documents')
    token = 'fixture-token-two'
    await client.get('/categories')
    token = null
    await client.get('/auth/me')
    expect(configs.map(value => value.baseURL)).toEqual(['/api', '/api', '/api'])
    expect(configs.map(value => value.timeout)).toEqual([60000, 60000, 60000])
    expect(configs.map(value => value.headers.get('Authorization'))).toEqual([
      'Bearer fixture-token-one',
      'Bearer fixture-token-two',
      undefined
    ])
  })
  it.each([401, 403, 404, 409, 422, 500])(
    'preserves HTTP %i and server business details',
    async status => {
      const expire = vi.fn()
      const notify = vi.fn()
      const client = createApiClient({
        getToken: () => 'fixture-token',
        onSessionExpired: expire,
        onError: notify
      })
      client.defaults.adapter = rejected(status, status * 100 + 1)
      await expect(client.get('/documents')).rejects.toMatchObject({
        status,
        code: status * 100 + 1,
        requestId: 'server-error-id',
        message: '可读错误原因'
      })
      expect(expire).toHaveBeenCalledTimes(status === 401 ? 1 : 0)
      expect(notify).toHaveBeenCalledOnce()
    }
  )
  it('leaves wrong login credentials in the form without clearing a session or redirecting', async () => {
    const expire = vi.fn()
    const notify = vi.fn()
    const client = createApiClient({
      getToken: () => 'existing-fixture',
      onSessionExpired: expire,
      onError: notify
    })
    client.defaults.adapter = rejected(401, 40102)
    await expect(
      client.post('/auth/login', { username: 'fixture_user', password: 'fixture_only' })
    ).rejects.toMatchObject({ code: 40102 })
    expect(expire).not.toHaveBeenCalled()
    expect(notify).not.toHaveBeenCalled()
  })
  it('coalesces simultaneous expired/disabled sessions', async () => {
    const expire = vi.fn(async () => {
      await new Promise(resolve => setTimeout(resolve, 5))
    })
    const client = createApiClient({ getToken: () => 'fixture-token', onSessionExpired: expire })
    client.defaults.adapter = rejected(401, 40103)
    await Promise.allSettled([
      client.get('/documents'),
      client.get('/qa/history'),
      client.get('/favorites')
    ])
    expect(expire).toHaveBeenCalledOnce()
  })
  it('does not clear a newly logged-in account when an older request receives 401', async () => {
    let token = 'old-fixture'
    const expire = vi.fn()
    const client = createApiClient({ getToken: () => token, onSessionExpired: expire })
    client.defaults.adapter = async config => {
      token = 'new-fixture'
      return rejected(401, 40101)(config)
    }
    await expect(client.get('/auth/me')).rejects.toBeInstanceOf(ApiError)
    expect(expire).not.toHaveBeenCalled()
  })
  it.each(['SUCCESS', 'NO_EVIDENCE', 'FAILED'])(
    'preserves HTTP 200 QA business state %s',
    async status => {
      const notify = vi.fn()
      const client = createApiClient({ getToken: () => null, onError: notify })
      client.defaults.adapter = async config => ({
        data: success({ qaId: 41, status, answer: null, sources: [] }),
        status: 200,
        statusText: 'ok',
        headers: {},
        config
      })
      expect((await client.post('/qa', { question: 'fixture' })).data.data.status).toBe(status)
      expect(notify).not.toHaveBeenCalled()
    }
  )
  it('rejects nonzero envelopes and malformed success responses', async () => {
    const notify = vi.fn()
    const client = createApiClient({ getToken: () => null, onError: notify })
    client.defaults.adapter = async config => ({
      data: { ...success(), code: 42201, message: '字段不合法' },
      status: 200,
      statusText: 'ok',
      headers: {},
      config
    })
    await expect(client.get('/documents')).rejects.toMatchObject({
      code: 42201,
      message: '字段不合法',
      kind: 'business'
    })
    client.defaults.adapter = async config => ({
      data: '<html>not API</html>',
      status: 200,
      statusText: 'ok',
      headers: { 'x-request-id': 'header-id' },
      config
    })
    await expect(client.get('/documents')).rejects.toMatchObject({
      kind: 'protocol',
      requestId: 'header-id'
    })
    expect(notify).toHaveBeenCalledTimes(2)
  })
  it.each([401, 403, 404, 422, 500])(
    'decodes HTTP %i JSON errors delivered as blobs',
    async status => {
      const client = createApiClient({ getToken: () => null })
      client.defaults.adapter = rejected(status, status * 100 + 1, true)
      await expect(
        client.get('/documents/12/download', { responseType: 'blob' })
      ).rejects.toMatchObject({ status, code: status * 100 + 1, requestId: 'server-error-id' })
    }
  )
  it('rejects JSON on successful blob paths and keeps real file bytes', async () => {
    const client = createApiClient({ getToken: () => null })
    client.defaults.adapter = async config => ({
      data: new Blob([JSON.stringify({ ...success(), code: 40402, message: '原件缺失' })], {
        type: 'application/json'
      }),
      status: 200,
      statusText: 'ok',
      headers: {},
      config
    })
    await expect(
      client.get('/documents/12/download', { responseType: 'blob' })
    ).rejects.toMatchObject({ code: 40402, message: '原件缺失' })
    const file = new Blob(['%PDF-fixture'], { type: 'application/pdf' })
    client.defaults.adapter = async config => ({
      data: file,
      status: 200,
      statusText: 'ok',
      headers: { 'content-type': 'application/pdf' },
      config
    })
    expect((await client.get('/documents/12/download', { responseType: 'blob' })).data).toBe(file)
  })
  it('classifies offline and timeout failures without exposing request credentials or retrying', async () => {
    const client = createApiClient({ getToken: () => 'fixture-token' })
    const adapter = vi.fn<AxiosAdapter>()
    client.defaults.adapter = adapter
    adapter.mockImplementation(async config => {
      throw new AxiosError('internal diagnostic', 'ERR_NETWORK', config)
    })
    await expect(client.get('/documents')).rejects.toMatchObject({ kind: 'network', status: null })
    adapter.mockImplementation(async config => {
      throw new AxiosError('internal diagnostic', 'ECONNABORTED', config)
    })
    const error: unknown = await client
      .post('/qa', { question: 'fixture' }, { timeout: QA_TIMEOUT })
      .catch(value => value)
    expect(error).toMatchObject({
      kind: 'timeout',
      message: expect.stringContaining('先查看历史记录')
    })
    expect(error).not.toHaveProperty('config')
    expect(JSON.stringify(error)).not.toContain('fixture-token')
    expect(adapter).toHaveBeenCalledTimes(2)
  })
})
