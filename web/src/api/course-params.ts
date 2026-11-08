import type {
  AdminDocumentFilters,
  DateFilters,
  FeedbackFilters,
  HistoryFilters,
  Pagination,
  UserFilters
} from './course-types'

const text = (value?: string) => value?.trim() || undefined
export const pageParams = (value: Pagination = {}) => ({
  page: value.page ?? 1,
  pageSize: value.pageSize ?? 10
})
export const dateParams = (value: DateFilters = {}) => ({
  from: text(value.from),
  to: text(value.to)
})
export const documentParams = (value: AdminDocumentFilters = {}) => ({
  ...pageParams(value),
  categoryId: value.categoryId,
  title: text(value.title),
  keyword: text(value.keyword)
})
export const adminDocumentParams = (value: AdminDocumentFilters = {}) => ({
  ...documentParams(value),
  status: value.status
})
export const historyParams = (value: HistoryFilters = {}) => ({
  ...pageParams(value),
  status: value.status
})
export const feedbackParams = (value: FeedbackFilters = {}) => ({
  ...pageParams(value),
  ...dateParams(value),
  rating: value.rating
})
export const userParams = (value: UserFilters = {}) => ({
  ...pageParams(value),
  keyword: text(value.keyword),
  status: value.status
})
