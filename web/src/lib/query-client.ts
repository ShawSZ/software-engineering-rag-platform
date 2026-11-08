import { QueryClient } from '@tanstack/vue-query'
import { ApiError } from '@/utils/http-client'

export function retryQuery(failures: number, error: unknown) {
  return (
    failures < 1 &&
    (!(error instanceof ApiError) ||
      (error.kind !== 'cancelled' &&
        error.kind !== 'protocol' &&
        (error.status === null || error.status >= 500)))
  )
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: retryQuery,
      refetchOnWindowFocus: false
    },
    mutations: { retry: false }
  }
})
