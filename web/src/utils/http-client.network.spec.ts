// @vitest-environment node
import { createServer } from 'node:http'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { createApiClient } from './http-client'

const calls: { url: string; contentType: string; authorization: string; body: string }[] = []
const server = createServer(async (request, response) => {
  const chunks: Buffer[] = []
  for await (const chunk of request) chunks.push(Buffer.from(chunk))
  calls.push({
    url: request.url || '',
    contentType: request.headers['content-type'] || '',
    authorization: request.headers.authorization || '',
    body: Buffer.concat(chunks).toString()
  })
  response.setHeader('Content-Type', 'application/json')
  if (request.url === '/api/missing') {
    response.statusCode = 404
    response.end(
      JSON.stringify({
        code: 40402,
        message: '合成原件缺失',
        requestId: 'network-fixture',
        data: null
      })
    )
  } else
    response.end(
      JSON.stringify({ code: 0, message: 'success', requestId: 'network-fixture', data: null })
    )
})
let origin = ''
beforeAll(async () => {
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  if (!address || typeof address === 'string') throw new Error('No loopback address')
  origin = `http://127.0.0.1:${address.port}/api`
})
afterAll(async () => {
  server.closeAllConnections()
  await new Promise<void>(resolve => server.close(() => resolve()))
})
describe('real HTTP adapter', () => {
  it('lets FormData choose a working multipart boundary and sends Bearer once', async () => {
    const client = createApiClient({ getToken: () => 'network-fixture-token' })
    client.defaults.baseURL = origin
    client.defaults.adapter = 'fetch'
    const form = new FormData()
    form.append('file', new Blob(['synthetic original'], { type: 'text/plain' }), 'fixture.txt')
    await client.post('/upload', form)
    const call = calls.at(-1)!
    expect(call.authorization).toBe('Bearer network-fixture-token')
    expect(call.contentType).toMatch(/^multipart\/form-data; boundary=/)
    expect(call.body).toContain('synthetic original')
    expect(call.body).toContain('name="file"; filename="fixture.txt"')
    expect(call.body).toContain(call.contentType.split('boundary=')[1])
  })
  it('decodes a real HTTP error blob into an API error rather than file bytes', async () => {
    const client = createApiClient({ getToken: () => null })
    client.defaults.baseURL = origin
    client.defaults.adapter = 'fetch'
    await expect(client.get('/missing', { responseType: 'blob' })).rejects.toMatchObject({
      status: 404,
      code: 40402,
      requestId: 'network-fixture',
      message: '合成原件缺失'
    })
  })
})
