import axios, { AxiosHeaders, type AxiosInstance } from 'axios'
import type { ApiResponse } from '@/api/course-types'
import { API_BASE } from './api-base'

export const REQUEST_TIMEOUT = 60_000
export const QA_TIMEOUT = 40_000
export type ApiErrorKind = 'http' | 'business' | 'network' | 'timeout' | 'protocol' | 'cancelled'
export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number | null,
    public readonly code: number | null,
    public readonly requestId: string | null,
    public readonly kind: ApiErrorKind
  ) {
    super(message)
    this.name = 'ApiError'
  }
}
interface ClientOptions {
  getToken: () => string | null
  onSessionExpired?: (token: string) => void | Promise<void>
  onError?: (error: ApiError) => void
}
function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
function envelope(value: unknown): value is ApiResponse<unknown> {
  return (
    object(value) &&
    typeof value.code === 'number' &&
    typeof value.message === 'string' &&
    typeof value.requestId === 'string' &&
    'data' in value
  )
}
async function decode(value: unknown): Promise<unknown> {
  if (!(value instanceof Blob)) return value
  const content =
    typeof value.text === 'function'
      ? await value.text()
      : await new Promise<string>((resolve, reject) => {
          const reader = new FileReader()
          reader.onload = () => resolve(String(reader.result))
          reader.onerror = () => reject(new Error('无法读取错误响应'))
          reader.readAsText(value)
        })
  try {
    return JSON.parse(content) as unknown
  } catch {
    return null
  }
}

/** Transport keeps standard Axios responses. Course APIs return the validated envelope. */
export function createApiClient(options: ClientOptions): AxiosInstance {
  const client = axios.create({ baseURL: API_BASE, timeout: REQUEST_TIMEOUT })
  let expiring: Promise<void> | null = null
  client.interceptors.request.use(config => {
    const token = options.getToken()
    if (token) config.headers.set('Authorization', `Bearer ${token}`)
    if (typeof FormData !== 'undefined' && config.data instanceof FormData)
      config.headers.setContentType(false)
    return config
  })
  client.interceptors.response.use(async response => {
    const value: unknown = response.data
    if (response.config.responseType === 'blob') {
      const contentType = String(
        response.headers['content-type'] || (value instanceof Blob ? value.type : '')
      )
      if (!/json/i.test(contentType)) return response
      const body = await decode(value)
      if (envelope(body) && body.code !== 0)
        throw new ApiError(body.message, response.status, body.code, body.requestId, 'business')
      throw new ApiError(
        '下载返回了 JSON 响应，请重新获取文件。',
        response.status,
        null,
        response.headers['x-request-id'] || null,
        'protocol'
      )
    }
    if (!envelope(value))
      throw new ApiError(
        '服务响应格式不符合约定。',
        response.status,
        null,
        response.headers['x-request-id'] || null,
        'protocol'
      )
    if (value.code !== 0)
      throw new ApiError(
        value.message || '请求失败',
        response.status,
        value.code,
        value.requestId,
        'business'
      )
    return response
  })
  client.interceptors.response.use(undefined, async (failure: unknown) => {
    let error: ApiError
    let requestToken: string | null = null
    let login = false
    if (failure instanceof ApiError) error = failure
    else if (axios.isAxiosError<unknown>(failure)) {
      const response = failure.response
      const body = await decode(response?.data).catch(() => null)
      const data = object(body) ? body : null
      const id =
        typeof data?.requestId === 'string'
          ? data.requestId
          : response?.headers['x-request-id'] || null
      const code = typeof data?.code === 'number' ? data.code : null
      const message = typeof data?.message === 'string' && data.message ? data.message : null
      const timeout = failure.code === 'ECONNABORTED' || failure.code === 'ETIMEDOUT'
      const qa =
        failure.config?.url?.replace(/\?.*$/, '') === '/qa' &&
        failure.config?.method?.toLowerCase() === 'post'
      const cancelled = axios.isCancel(failure)
      error = new ApiError(
        message ||
          (cancelled
            ? '请求已取消。'
            : timeout
              ? qa
                ? '问答请求超时，服务端可能仍在处理，请先查看历史记录，再决定是否重新提问。'
                : '请求超时，请稍后重试。'
              : response
                ? '请求失败，请稍后重试。'
                : '网络连接失败，请检查网络后重试。'),
        response?.status ?? null,
        code,
        id,
        cancelled ? 'cancelled' : timeout ? 'timeout' : response ? 'http' : 'network'
      )
      const authorization = AxiosHeaders.from(failure.config?.headers).get('Authorization')
      if (typeof authorization === 'string' && authorization.startsWith('Bearer '))
        requestToken = authorization.slice(7)
      login = failure.config?.url?.replace(/\?.*$/, '') === '/auth/login'
    } else error = new ApiError('请求失败，请稍后重试。', null, null, null, 'network')
    if (
      error.status === 401 &&
      error.code !== 40102 &&
      !login &&
      requestToken &&
      requestToken === options.getToken()
    ) {
      if (!expiring) {
        expiring = Promise.resolve()
          .then(() => options.onSessionExpired?.(requestToken!))
          .catch(() => undefined)
          .finally(() => {
            expiring = null
          })
      }
      await expiring
    }
    if (error.kind !== 'cancelled' && error.code !== 40102) options.onError?.(error)
    throw error
  })
  return client
}
