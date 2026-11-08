import { describe, expect, it, vi } from 'vitest'
import { MutationObserver, QueryClient } from '@tanstack/vue-query'
import { courseQueryKeys as keys } from './course-query-keys'
import { queryClient, retryQuery } from './query-client'
import { ApiError } from '@/utils/http-client'

describe('course query and mutation policies', () => {
  it('isolates all private and permission-sensitive keys by authenticated user', () => {
    expect(keys.documents(4)).not.toEqual(keys.documents(5))
    expect(keys.history(4)).not.toEqual(keys.history(5))
    expect(keys.source(4, 12, 13)).not.toEqual(keys.source(5, 12, 13))
    expect(keys.documents(4)).not.toEqual(keys.adminDocuments(4))
  })
  it('normalizes equivalent filters and separates every paging/date/status/location filter', () => {
    expect(keys.documents(4, { title: ' hi ', keyword: ' ' })).toEqual(
      keys.documents(4, { title: 'hi', page: 1, pageSize: 10 })
    )
    expect(keys.documents(4, { categoryId: 1 })).not.toEqual(keys.documents(4, { categoryId: 2 }))
    expect(keys.history(4, { status: 'SUCCESS' })).not.toEqual(
      keys.history(4, { status: 'FAILED' })
    )
    expect(keys.favorites(4, { page: 1 })).not.toEqual(keys.favorites(4, { page: 2 }))
    expect(keys.chunks(4, 1, { chunkIndex: 1 })).not.toEqual(keys.chunks(4, 1, { chunkIndex: 2 }))
    expect(keys.stats(4, { to: '2026-10-29' })).not.toEqual(keys.stats(4, { to: '2026-10-30' }))
    expect(keys.feedback(4, { rating: 'HELPFUL' })).not.toEqual(
      keys.feedback(4, { rating: 'UNHELPFUL' })
    )
    expect(keys.users(4, { keyword: ' hi ' })).toEqual(keys.users(4, { keyword: 'hi' }))
  })
  it('does not retry client/contract errors and caps transient query retries', () => {
    for (const status of [401, 403, 404, 409, 422])
      expect(retryQuery(0, new ApiError('fixture', status, null, null, 'http'))).toBe(false)
    expect(retryQuery(0, new ApiError('fixture', 500, null, null, 'http'))).toBe(true)
    expect(retryQuery(1, new ApiError('fixture', 500, null, null, 'http'))).toBe(false)
    expect(retryQuery(0, new ApiError('fixture', null, null, null, 'cancelled'))).toBe(false)
  })
  it('does not automatically repeat failed mutations such as questions or uploads', async () => {
    const client = new QueryClient({ defaultOptions: queryClient.getDefaultOptions() })
    const mutationFn = vi.fn().mockRejectedValue(new Error('fixture network failure'))
    const observer = new MutationObserver(client, { mutationFn })
    await expect(observer.mutate(undefined)).rejects.toThrow('fixture network failure')
    expect(mutationFn).toHaveBeenCalledOnce()
    client.clear()
  })
})
