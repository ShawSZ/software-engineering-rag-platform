import type { AxiosInstance } from 'axios'
import { courseHttp } from '@/utils/request'
import { QA_TIMEOUT } from '@/utils/http-client'
import {
  adminDocumentParams,
  dateParams,
  documentParams,
  feedbackParams,
  historyParams,
  pageParams,
  userParams
} from './course-params'
import type {
  AdminDocumentFilters,
  AdminFeedback,
  ApiResponse,
  Category,
  Chunk,
  DateFilters,
  Document,
  DocumentFilters,
  DocumentStatus,
  Feedback,
  FeedbackFilters,
  HistoryFilters,
  LoginResult,
  Page,
  Pagination,
  QaDetail,
  QaSummary,
  StudyKind,
  StudyResult,
  Rating,
  Source,
  Stats,
  User,
  UserFilters,
  UserStatus
} from './course-types'
export type * from './course-types'
export const COURSE_API_VERSION = 'COURSE-API-1.0'
export interface Download {
  blob: Blob
  filename: string
  contentType: string
}

export function downloadFilename(header: string | undefined, fallback: string): string {
  const encoded = header?.match(/filename\*\s*=\s*"?UTF-8'[^']*'([^";]*)/i)?.[1]
  const plain = header?.match(/filename\s*=\s*(?:"((?:\\.|[^"])*)"|([^;]*))/i)
  let value = plain?.[1]?.replace(/\\(.)/g, '$1') ?? plain?.[2]?.trim() ?? fallback
  if (encoded) {
    try {
      value = decodeURIComponent(encoded)
    } catch {
      /* Use the plain filename. */
    }
  }
  const name = value.split(/[\\/]/).pop() || ''
  return (
    Array.from(name)
      .filter(char => char.charCodeAt(0) > 31 && char.charCodeAt(0) !== 127)
      .join('')
      .trim() || fallback
  )
}
export function createCourseApi(http: AxiosInstance) {
  const get = async <T>(url: string, params?: object): Promise<ApiResponse<T>> =>
    (await http.get<ApiResponse<T>>(url, { params })).data
  const post = async <T>(url: string, body?: object, timeout?: number): Promise<ApiResponse<T>> =>
    (await http.post<ApiResponse<T>>(url, body, { timeout })).data
  const patch = async <T>(url: string, body: object): Promise<ApiResponse<T>> =>
    (await http.patch<ApiResponse<T>>(url, body)).data
  const remove = async <T>(url: string): Promise<ApiResponse<T>> =>
    (await http.delete<ApiResponse<T>>(url)).data
  return {
    auth: {
      register: (input: { username: string; password: string; displayName?: string }) =>
        post<User>('/auth/register', {
          username: input.username,
          password: input.password,
          ...(input.displayName === undefined ? {} : { displayName: input.displayName })
        }),
      login: (username: string, password: string) =>
        post<LoginResult>('/auth/login', { username, password }),
      logout: () => post<null>('/auth/logout'),
      me: () => get<User>('/auth/me')
    },
    categories: {
      list: () => get<Category[]>('/categories'),
      create: (name: string, sortOrder = 0) => post<Category>('/categories', { name, sortOrder }),
      update: (id: number, name: string, sortOrder: number) =>
        patch<Category>(`/categories/${id}`, { name, sortOrder }),
      remove: (id: number) => remove<null>(`/categories/${id}`)
    },
    documents: {
      list: (filters?: DocumentFilters) =>
        get<Page<Document>>('/documents', documentParams(filters)),
      adminList: (filters?: AdminDocumentFilters) =>
        get<Page<Document>>('/admin/documents', adminDocumentParams(filters)),
      detail: (id: number) => get<Document>(`/documents/${id}`),
      status: (id: number) => get<DocumentStatus>(`/documents/${id}/status`),
      chunks: (id: number, filters: Pagination & { chunkIndex?: number } = {}) =>
        get<Page<Chunk>>(`/documents/${id}/chunks`, {
          ...pageParams(filters),
          chunkIndex: filters.chunkIndex
        }),
      upload: (file: File, title: string, categoryId: number) => {
        const form = new FormData()
        form.append('file', file)
        form.append('title', title)
        form.append('categoryId', String(categoryId))
        return post<Document>('/documents', form)
      },
      replace: async (id: number, file: File) => {
        const form = new FormData()
        form.append('file', file)
        return (await http.put<ApiResponse<Document>>(`/documents/${id}/file`, form)).data
      },
      update: (id: number, title: string, categoryId: number) =>
        patch<Document>(`/documents/${id}`, { title, categoryId }),
      offline: (id: number) => remove<null>(`/documents/${id}`),
      download: async (id: number): Promise<Download> => {
        const response = await http.get<Blob>(`/documents/${id}/download`, { responseType: 'blob' })
        return {
          blob: response.data,
          filename: downloadFilename(response.headers['content-disposition'], `document-${id}`),
          contentType: String(response.headers['content-type'] || response.data.type)
        }
      }
    },
    qa: {
      ask: (question: string, parentQaId?: number) =>
        post<QaDetail>(
          '/qa',
          { question, ...(parentQaId === undefined ? {} : { parentQaId }) },
          QA_TIMEOUT
        ),
      conversation: (id: number) => get<QaDetail[]>(`/qa/${id}/conversation`),
      history: (filters?: HistoryFilters) =>
        get<Page<QaSummary>>('/qa/history', historyParams(filters)),
      detail: (id: number) => get<QaDetail>(`/qa/${id}`),
      source: (id: number, sourceId: number) => get<Source>(`/qa/${id}/sources/${sourceId}`),
      favorites: (filters?: Pagination) => get<Page<QaSummary>>('/favorites', pageParams(filters)),
      favorite: async (id: number) =>
        (await http.put<ApiResponse<{ qaId: number; favorited: boolean }>>(`/qa/${id}/favorite`))
          .data,
      unfavorite: (id: number) =>
        remove<{ qaId: number; favorited: boolean }>(`/qa/${id}/favorite`),
      feedback: (id: number, rating: Rating, reason?: string) =>
        post<Feedback>(`/qa/${id}/feedback`, {
          rating,
          ...(reason === undefined ? {} : { reason })
        })
    },
    study: {
      generate: (documentId: number, kind: StudyKind, questionCount?: number) =>
        post<StudyResult>(
          '/study',
          {
            documentId,
            kind,
            ...(kind === 'EXERCISES' ? { questionCount: questionCount ?? 3 } : {})
          },
          QA_TIMEOUT
        )
    },
    admin: {
      users: (filters?: UserFilters) => get<Page<User>>('/admin/users', userParams(filters)),
      setUserStatus: (id: number, status: UserStatus) =>
        patch<User>(`/admin/users/${id}/status`, { status }),
      feedback: (filters?: FeedbackFilters) =>
        get<Page<AdminFeedback>>('/admin/feedback', feedbackParams(filters)),
      stats: (filters?: DateFilters) => get<Stats>('/admin/stats', dateParams(filters))
    }
  }
}
export const courseApi = createCourseApi(courseHttp)
