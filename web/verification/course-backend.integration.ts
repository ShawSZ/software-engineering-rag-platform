import { createHash } from 'node:crypto'
import { describe, expect, it, vi } from 'vitest'
import { createApiClient } from '../src/utils/http-client'
vi.mock('../src/utils/request', () => ({ courseHttp: undefined }))
import { createCourseApi } from '../src/api/course'

const base = process.env.F01_BACKEND_URL
const password = process.env.F01_ADMIN_PASSWORD
if (!base || !password || !/^http:\/\/127\.0\.0\.1:\d+\/api$/.test(base))
  throw new Error('Start the explicitly isolated F01 backend probe first')

describe('F01 real course backend', () => {
  it('uses the frontend transport for authentication, canonical pages, multipart, download and QA states', async () => {
    let token: string | null = null
    const client = createApiClient({ getToken: () => token })
    client.defaults.baseURL = base
    client.defaults.adapter = 'fetch'
    const api = createCourseApi(client)
    const logged = await api.auth.login('accept_admin', password)
    token = logged.data.token
    expect(logged.data.role).toBe('ADMIN')
    expect(typeof logged.requestId).toBe('string')
    const me = await api.auth.me()
    expect(me.data.role).toBe('ADMIN')
    const category = await api.categories.create('F01 synthetic material')
    const text = 'Functional requirements specify services and observable system behavior.'
    const file = new File([text], '合成资料.txt', { type: 'text/plain' })
    const created = await api.documents.upload(file, 'F01 synthetic', category.data.id)
    expect(created.data.latestVersion.status).toBe('QUEUED')
    let state = await api.documents.status(created.data.id)
    for (
      let attempt = 0;
      attempt < 100 && ['QUEUED', 'PARSING'].includes(state.data.latestVersion.status);
      attempt++
    ) {
      await new Promise(resolve => setTimeout(resolve, 50))
      state = await api.documents.status(created.data.id)
    }
    expect(state.data.currentVersion?.status).toBe('READY')
    const listed = await api.documents.list({ categoryId: category.data.id, keyword: 'Functional' })
    expect(listed.data.total).toBe(1)
    expect(listed.data.page).toBe(1)
    expect(listed.data.pageSize).toBe(10)
    expect(listed.data.items[0]?.id).toBe(created.data.id)
    expect(listed.data).not.toHaveProperty('records')
    const download = await api.documents.download(created.data.id)
    expect(download.filename).toBe('合成资料.txt')
    const downloaded = Buffer.from(await download.blob.arrayBuffer())
    expect(createHash('sha256').update(downloaded).digest('hex')).toBe(
      createHash('sha256').update(text).digest('hex')
    )
    const result = await api.qa.ask('What do functional requirements specify?')
    expect(result.data.status).toBe('FAILED')
    expect(result.data.errorCode).toBe('MODEL_NOT_CONFIGURED')
    expect(result.data.answer).toBeNull()
    expect(result.data.sources).toEqual([])
    const history = await api.qa.history()
    expect(history.data.total).toBe(1)
    expect(history.data.items[0]?.qaId).toBe(result.data.qaId)
    await expect(api.documents.download(9007199254740991)).rejects.toMatchObject({
      status: 404,
      code: 40401,
      requestId: expect.any(String)
    })
    await expect(api.documents.list({ pageSize: 51 })).rejects.toMatchObject({
      status: 422,
      code: 42201
    })
    await api.auth.logout()
    await expect(api.auth.me()).rejects.toMatchObject({ status: 401, code: 40101 })
  })
})
