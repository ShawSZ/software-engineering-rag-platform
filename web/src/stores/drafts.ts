import { defineStore } from 'pinia'
import { reactive, ref, watch } from 'vue'

const PREFIX = 'course:draft:'
const OWNER = 'course:draft-owner'
export interface DocumentDraft {
  title: string
  keyword: string
  categoryId?: number
}
function clearStored() {
  for (let i = sessionStorage.length - 1; i >= 0; i--) {
    const key = sessionStorage.key(i)
    if (key?.startsWith(PREFIX) || key === OWNER) sessionStorage.removeItem(key)
  }
}
function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
/** Only these explicit, non-sensitive fields may survive an expired session. */
export const useDraftStore = defineStore('course-drafts', () => {
  const owner = ref<number | null>(null)
  const question = ref('')
  const documents = reactive<DocumentDraft>({ title: '', keyword: '' })
  function resetMemory() {
    owner.value = null
    question.value = ''
    documents.title = ''
    documents.keyword = ''
    documents.categoryId = undefined
  }
  function persist() {
    if (!owner.value) return
    try {
      sessionStorage.setItem(OWNER, String(owner.value))
      sessionStorage.setItem(
        PREFIX + owner.value,
        JSON.stringify({
          question: question.value,
          documents: {
            title: documents.title,
            keyword: documents.keyword,
            categoryId: documents.categoryId
          }
        })
      )
    } catch {
      /* Storage limits must not prevent logging out. */
    }
  }
  watch([question, documents], persist, { deep: true, flush: 'sync' })
  function clear() {
    resetMemory()
    clearStored()
  }
  function activate(userId: number) {
    const previous = sessionStorage.getItem(OWNER)
    resetMemory()
    if (previous && previous !== String(userId)) clearStored()
    let saved: unknown
    try {
      saved = JSON.parse(sessionStorage.getItem(PREFIX + userId) || 'null')
    } catch {
      saved = null
    }
    if (object(saved)) {
      question.value = typeof saved.question === 'string' ? saved.question : ''
      if (object(saved.documents)) {
        documents.title = typeof saved.documents.title === 'string' ? saved.documents.title : ''
        documents.keyword =
          typeof saved.documents.keyword === 'string' ? saved.documents.keyword : ''
        const categoryId = saved.documents.categoryId
        documents.categoryId =
          typeof categoryId === 'number' && Number.isSafeInteger(categoryId) && categoryId > 0
            ? categoryId
            : undefined
      }
    }
    owner.value = userId
    persist()
  }
  function suspend() {
    persist()
    resetMemory()
  }
  return { question, documents, owner, activate, suspend, clear }
})
