import {
  adminDocumentParams,
  dateParams,
  documentParams,
  feedbackParams,
  historyParams,
  pageParams,
  userParams
} from '@/api/course-params'
import type {
  AdminDocumentFilters,
  DateFilters,
  DocumentFilters,
  FeedbackFilters,
  HistoryFilters,
  Pagination,
  UserFilters
} from '@/api/course-types'

export const courseQueryKeys = {
  all: ['course'] as const,
  user: (userId: number) => ['course', userId] as const,
  me: (userId: number) => ['course', userId, 'me'] as const,
  categories: (userId: number) => ['course', userId, 'categories'] as const,
  documents: (userId: number, filters?: DocumentFilters) =>
    ['course', userId, 'documents', documentParams(filters)] as const,
  adminDocuments: (userId: number, filters?: AdminDocumentFilters) =>
    ['course', userId, 'admin-documents', adminDocumentParams(filters)] as const,
  document: (userId: number, id: number) => ['course', userId, 'document', id] as const,
  documentStatus: (userId: number, id: number) =>
    ['course', userId, 'document', id, 'status'] as const,
  chunks: (userId: number, id: number, filters: Pagination & { chunkIndex?: number } = {}) =>
    [
      'course',
      userId,
      'document',
      id,
      'chunks',
      { ...pageParams(filters), chunkIndex: filters.chunkIndex }
    ] as const,
  history: (userId: number, filters?: HistoryFilters) =>
    ['course', userId, 'history', historyParams(filters)] as const,
  qa: (userId: number, id: number) => ['course', userId, 'qa', id] as const,
  source: (userId: number, id: number, sourceId: number) =>
    ['course', userId, 'qa', id, 'source', sourceId] as const,
  favorites: (userId: number, filters?: Pagination) =>
    ['course', userId, 'favorites', pageParams(filters)] as const,
  users: (userId: number, filters?: UserFilters) =>
    ['course', userId, 'users', userParams(filters)] as const,
  feedback: (userId: number, filters?: FeedbackFilters) =>
    ['course', userId, 'feedback', feedbackParams(filters)] as const,
  stats: (userId: number, filters?: DateFilters) =>
    ['course', userId, 'stats', dateParams(filters)] as const
}
