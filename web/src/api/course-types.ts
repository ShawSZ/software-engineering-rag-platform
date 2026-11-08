export type Role = 'USER' | 'ADMIN'
export type UserStatus = 'ENABLED' | 'DISABLED'
export type ParseStatus = 'QUEUED' | 'PARSING' | 'READY' | 'FAILED'
export type DocumentState = 'ACTIVE' | 'OFFLINE'
export type QaStatus = 'SUCCESS' | 'NO_EVIDENCE' | 'FAILED'
export type Rating = 'HELPFUL' | 'UNHELPFUL'
export type Availability = 'CURRENT' | 'UPDATED' | 'OFFLINE'
export type ParseErrorCode = 'EMPTY_TEXT' | 'PDF_PARSE_ERROR' | 'OFFICE_PARSE_ERROR' | 'PARSE_ERROR'
export type QaErrorCode =
  | 'EVIDENCE_INSUFFICIENT'
  | 'MODEL_NOT_CONFIGURED'
  | 'MODEL_TIMEOUT'
  | 'MODEL_SERVICE_ERROR'
  | 'INVALID_CITATION'
  | 'INVALID_GENERATION'
  | 'EVIDENCE_CHANGED'
  | 'RETRIEVAL_ERROR'
  | 'INTERNAL_ERROR'
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  requestId: string
}
export interface Page<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}
export interface Pagination {
  page?: number
  pageSize?: number
}
export interface User {
  userId: number
  username: string
  displayName: string
  role: Role
  status: UserStatus
  createdAt: string
}
export interface LoginResult {
  token: string
  userId: number
  username: string
  displayName: string
  role: Role
}
export interface Category {
  id: number
  name: string
  sortOrder: number
}
export interface Version {
  id: number
  versionNumber: number
  status: ParseStatus
  originalFilename: string
  fileType: 'PDF' | 'TXT' | 'DOCX' | 'PPTX'
  fileSize: number
  chunkCount: number
  errorCode: ParseErrorCode | null
  errorMessage: string | null
  createdAt: string
}
export interface Document {
  id: number
  title: string
  categoryId: number
  categoryName: string
  status: DocumentState
  currentVersion: Version | null
  latestVersion: Version
  createdAt: string
  updatedAt: string
}
export interface DocumentStatus {
  documentId: number
  status: DocumentState
  currentVersion: Version | null
  latestVersion: Version
}
export interface Chunk {
  chunkId: number
  versionId: number
  pageNo: number | null
  chunkIndex: number
  content: string
}
export interface Source {
  sourceId: number
  evidenceLabel: string
  documentId: number
  versionId: number
  versionNumber: number
  chunkId: number
  documentTitle: string
  pageNo: number | null
  chunkIndex: number
  excerpt: string
  availability: Availability
}
export interface Feedback {
  id: number
  qaId: number
  rating: Rating
  reason: string
  updatedAt: string
}
export interface QaResult {
  qaId: number
  status: QaStatus
  answer: string | null
  sources: Source[]
  latencyMs: number
  errorCode: QaErrorCode | null
  errorMessage: string | null
  retryable: boolean
}
export interface QaDetail extends QaResult {
  parentQaId?: number | null
  question: string
  createdAt: string
  favorited: boolean
  feedback: Feedback | null
}
export interface QaSummary {
  qaId: number
  question: string
  status: QaStatus
  answerPreview: string | null
  latencyMs: number
  errorCode: QaErrorCode | null
  errorMessage: string | null
  createdAt: string
  favorited: boolean
}
export type StudyKind = 'OUTLINE' | 'EXERCISES'
export interface StudyResult {
  kind: StudyKind
  documentId: number
  selectedChunkCount: number
  totalChunkCount: number
  result: QaDetail
}
export interface AdminFeedback extends Feedback {
  userId: number
  username: string
  question: string
}
export interface Stats {
  from: string | null
  to: string | null
  documentCount: number
  userCount: number
  qaSuccessCount: number
  qaNoEvidenceCount: number
  qaFailedCount: number
  feedbackHelpfulCount: number
  feedbackUnhelpfulCount: number
}
export interface DocumentFilters extends Pagination {
  categoryId?: number
  title?: string
  keyword?: string
}
export interface AdminDocumentFilters extends DocumentFilters {
  status?: DocumentState
}
export interface HistoryFilters extends Pagination {
  status?: QaStatus
}
export interface DateFilters {
  from?: string
  to?: string
}
export interface FeedbackFilters extends Pagination, DateFilters {
  rating?: Rating
}
export interface UserFilters extends Pagination {
  keyword?: string
  status?: UserStatus
}
