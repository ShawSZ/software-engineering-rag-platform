import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { VueQueryPlugin } from '@tanstack/vue-query'
import ElementPlus, { ElSelect, ElRadioGroup, ElDatePicker, ElMessageBox } from 'element-plus'
import type { Component } from 'vue'
import { queryClient } from '@/lib/query-client'
import { useAuthStore } from '@/stores/auth'
import { useDraftStore } from '@/stores/drafts'
import { ApiError } from '@/utils/http-client'
import { fileError } from '@/composables/useCourse'
import type { Document, Version, QaDetail, Source, Stats, ApiResponse } from '@/api/course-types'
import DocumentsPage from '@/views/course/DocumentsPage.vue'
import DocumentForm from './DocumentForm.vue'
import TablePager from '@/components/TablePager.vue'
import VersionState from './VersionState.vue'
import QaPage from '@/views/course/QaPage.vue'
import StudyPage from '@/views/course/StudyPage.vue'
import QaResult from './QaResult.vue'
import SourceList from './CourseSourceList.vue'
import HistoryPage from '@/views/course/HistoryPage.vue'
import HistoryDetail from '@/views/course/HistoryDetail.vue'
import CategoriesPage from '@/views/course/CategoriesPage.vue'
import UsersPage from '@/views/course/UsersPage.vue'
import StatsPage from '@/views/course/StatsPage.vue'
import FeedbackPage from '@/views/course/FeedbackPage.vue'

const api = vi.hoisted(() => ({
  auth: { login: vi.fn(), me: vi.fn(), logout: vi.fn() },
  categories: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
  documents: {
    list: vi.fn(),
    adminList: vi.fn(),
    detail: vi.fn(),
    status: vi.fn(),
    chunks: vi.fn(),
    upload: vi.fn(),
    replace: vi.fn(),
    update: vi.fn(),
    offline: vi.fn(),
    download: vi.fn()
  },
  qa: {
    ask: vi.fn(),
    conversation: vi.fn(),
    history: vi.fn(),
    detail: vi.fn(),
    source: vi.fn(),
    favorites: vi.fn(),
    favorite: vi.fn(),
    unfavorite: vi.fn(),
    feedback: vi.fn()
  },
  study: { generate: vi.fn() },
  admin: { users: vi.fn(), setUserStatus: vi.fn(), feedback: vi.fn(), stats: vi.fn() }
}))
vi.mock('@/api/course', () => ({ courseApi: api }))
const now = '2026-10-29T12:00:00+08:00'
const version: Version = {
  id: 11,
  versionNumber: 1,
  status: 'READY',
  originalFilename: 'fixture.txt',
  fileType: 'TXT',
  fileSize: 12,
  chunkCount: 1,
  errorCode: null,
  errorMessage: null,
  createdAt: now
}
const documentFixture: Document = {
  id: 1,
  title: '需求讲义',
  categoryId: 1,
  categoryName: '讲义',
  status: 'ACTIVE',
  currentVersion: version,
  latestVersion: version,
  createdAt: now,
  updatedAt: now
}
const source: Source = {
  sourceId: 51,
  evidenceLabel: 'S1',
  documentId: 1,
  versionId: 11,
  versionNumber: 1,
  chunkId: 101,
  documentTitle: '当时的资料标题',
  pageNo: null,
  chunkIndex: 1,
  excerpt: '需求应明确服务与约束。',
  availability: 'CURRENT'
}
const answer: QaDetail = {
  qaId: 31,
  status: 'SUCCESS',
  question: '什么是需求？',
  answer: '需求描述服务与约束。[S1]',
  sources: [source],
  latencyMs: 25,
  errorCode: null,
  errorMessage: null,
  retryable: false,
  createdAt: now,
  favorited: false,
  feedback: null
}
const zeroStats: Stats = {
  from: null,
  to: null,
  documentCount: 0,
  userCount: 0,
  qaSuccessCount: 0,
  qaNoEvidenceCount: 0,
  qaFailedCount: 0,
  feedbackHelpfulCount: 0,
  feedbackUnhelpfulCount: 0
}
const ok = <T>(data: T): ApiResponse<T> => ({
  code: 0,
  message: 'success',
  data,
  requestId: 'fixture'
})
const page = <T>(items: T[], total = items.length) => ({ items, total, page: 1, pageSize: 10 })
let wrapper: VueWrapper | undefined, router: Router
beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  sessionStorage.clear()
  queryClient.clear()
  setActivePinia(createPinia())
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  )
  api.categories.list.mockResolvedValue(
    ok([
      { id: 1, name: '讲义', sortOrder: 0 },
      { id: 2, name: '练习', sortOrder: 1 }
    ])
  )
  api.documents.list.mockResolvedValue(ok(page([documentFixture])))
  api.documents.adminList.mockResolvedValue(ok(page([documentFixture])))
  api.documents.status.mockResolvedValue(
    ok({ documentId: 1, status: 'ACTIVE', currentVersion: version, latestVersion: version })
  )
  api.qa.ask.mockResolvedValue(ok(answer))
  api.study.generate.mockResolvedValue(
    ok({
      kind: 'OUTLINE',
      documentId: 1,
      selectedChunkCount: 1,
      totalChunkCount: 1,
      result: answer
    })
  )
  api.qa.conversation.mockResolvedValue(ok([answer]))
  api.qa.source.mockResolvedValue(ok(source))
  api.qa.detail.mockResolvedValue(ok(answer))
  api.qa.history.mockResolvedValue(
    ok(
      page([
        answer,
        {
          ...answer,
          qaId: 32,
          status: 'NO_EVIDENCE',
          answerPreview: null,
          errorMessage: '依据不足'
        },
        { ...answer, qaId: 33, status: 'FAILED', answerPreview: null, errorMessage: '模型超时' }
      ])
    )
  )
  api.qa.favorites.mockResolvedValue(ok(page([{ ...answer, favorited: true }])))
  api.qa.favorite.mockResolvedValue(ok({ qaId: 31, favorited: true }))
  api.qa.unfavorite.mockResolvedValue(ok({ qaId: 31, favorited: false }))
  api.qa.feedback.mockResolvedValue(
    ok({ id: 61, qaId: 31, rating: 'HELPFUL', reason: '来源清楚', updatedAt: now })
  )
  api.admin.users.mockResolvedValue(
    ok(
      page([
        {
          userId: 7,
          username: 'fixture_admin',
          displayName: '管理员',
          role: 'ADMIN',
          status: 'ENABLED',
          createdAt: now
        }
      ])
    )
  )
  api.admin.feedback.mockResolvedValue(ok(page([])))
  api.admin.stats.mockResolvedValue(ok(zeroStats))
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  ElMessageBox.close()
  queryClient.clear()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  setActivePinia(undefined)
})
async function open(
  component: Component,
  path = '/documents',
  props: Record<string, unknown> = {},
  role: 'USER' | 'ADMIN' = 'ADMIN'
) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().applySession({
    token: 'flow-fixture',
    userId: 7,
    username: 'fixture_admin',
    displayName: 'Fixture',
    role
  })
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/history/:qaId', component: { template: '<span />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<span />' } }
    ]
  })
  await router.push(path)
  wrapper = mount(component, {
    props,
    global: { plugins: [pinia, router, ElementPlus, [VueQueryPlugin, { queryClient }]] }
  })
  await flushPromises()
  return wrapper
}
function button(text: string) {
  return wrapper!.findAll('button').find(item => item.text().trim() === text)!
}
async function confirm(text: string) {
  await vi.waitFor(() =>
    expect(
      Array.from(document.querySelectorAll<HTMLButtonElement>('.el-message-box button')).some(
        item => item.textContent?.trim() === text
      )
    ).toBe(true)
  )
  Array.from(document.querySelectorAll<HTMLButtonElement>('.el-message-box button'))
    .reverse()
    .find(item => item.textContent?.trim() === text)!
    .click()
  await flushPromises()
}

describe('course document interfaces', () => {
  it('pauses pending status checks when the document becomes hidden', async () => {
    vi.useFakeTimers()
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    const queued = { ...version, id: 12, versionNumber: 2, status: 'QUEUED' as const }
    api.documents.status.mockResolvedValue(
      ok({ documentId: 1, status: 'ACTIVE', currentVersion: version, latestVersion: queued })
    )
    await open(VersionState, '/admin/documents', {
      document: { ...documentFixture, latestVersion: queued }
    })
    expect(api.documents.status).toHaveBeenCalledTimes(1)
    visibility.mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(5000)
    expect(api.documents.status).toHaveBeenCalledTimes(1)
  })
  it('combines category, title and keyword and resets pagination when submitting filters', async () => {
    const ui = await open(DocumentsPage, '/documents?page=3&pageSize=20')
    await ui.get('input[placeholder="资料标题"]').setValue('  需求  ')
    await ui.get('input[placeholder="标题或正文关键词"]').setValue('  约束  ')
    ui.findComponent(ElSelect).vm.$emit('update:modelValue', 2)
    await ui.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({
      page: '1',
      pageSize: '20',
      categoryId: '2',
      title: '需求',
      keyword: '约束'
    })
    expect(api.documents.list).toHaveBeenLastCalledWith(
      expect.objectContaining({
        categoryId: 2,
        title: '需求',
        keyword: '约束',
        page: 1,
        pageSize: 20
      })
    )
    expect(useDraftStore().documents.title).toBe('需求')
  })
  it('distinguishes initial request failure from empty results', async () => {
    api.documents.list.mockRejectedValue(new ApiError('资料不可用', 404, 40401, 'fixture', 'http'))
    const ui = await open(DocumentsPage)
    expect(ui.text()).toContain('资料不可用')
    expect(ui.text()).not.toContain('没有符合条件的资料')
  })
  it('keeps the previous table on a background refresh failure', async () => {
    const ui = await open(DocumentsPage)
    api.documents.list.mockRejectedValue(new ApiError('刷新失败', 422, 42201, 'fixture', 'http'))
    await queryClient.invalidateQueries({ queryKey: ['course', 7, 'documents'] })
    await flushPromises()
    expect(ui.text()).toContain('需求讲义')
    expect(ui.text()).toContain('刷新失败')
  })
  it('rejects unsupported, empty and too-large files, while accepting exactly 20 MB', () => {
    expect(fileError(new File(['x'], 'test.doc'))).toContain('DOCX')
    expect(fileError(new File(['x'], 'test.docx'))).toBe('')
    expect(fileError(new File(['x'], 'test.PPTX'))).toBe('')
    expect(fileError(new File([], 'empty.txt'))).toContain('空文件')
    const exact = new File([new Uint8Array(20971520)], 'limit.pdf')
    expect(fileError(exact)).toBe('')
    expect(fileError(new File([exact, 'x'], 'large.pdf'))).toContain('20 MB')
  })
  it.each(['txt', 'docx', 'pptx'])('uploads %s only after a valid file, title and category are provided', async (extension) => {
    const ui = await open(DocumentForm, '/admin/documents', {
      open: true,
      mode: 'upload',
      categories: [{ id: 1, name: '讲义', sortOrder: 0 }]
    })
    expect(ui.get('input[type="file"]').attributes('accept')).toBe('.pdf,.txt,.docx,.pptx')
    await ui.get('form').trigger('submit')
    expect(api.documents.upload).not.toHaveBeenCalled()
    await ui.get('.el-input__inner').setValue(' 新资料 ')
    ui.findComponent(ElSelect).vm.$emit('update:modelValue', 1)
    const file = new File(['requirements'], `lecture.${extension}`)
    Object.defineProperty(ui.get('input[type="file"]').element, 'files', { value: [file] })
    await ui.get('input[type="file"]').trigger('change')
    api.documents.upload.mockResolvedValue(
      ok({
        ...documentFixture,
        currentVersion: null,
        latestVersion: { ...version, status: 'QUEUED' }
      })
    )
    await ui.get('form').trigger('submit')
    await flushPromises()
    expect(api.documents.upload).toHaveBeenCalledExactlyOnceWith(file, '新资料', 1)
    expect(ui.emitted('saved')).toHaveLength(1)
  })
  it('shows failed replacement and the still-available old version separately', async () => {
    const ui = await open(VersionState, '/admin/documents', {
      document: {
        ...documentFixture,
        latestVersion: {
          ...version,
          id: 12,
          versionNumber: 2,
          status: 'FAILED',
          errorCode: 'EMPTY_TEXT',
          errorMessage: '未提取到文字'
        }
      }
    })
    expect(ui.text()).toContain('当前 v1 可用')
    expect(ui.text()).toContain('新 v2')
    expect(ui.text()).toContain('旧版仍可用')
    expect(api.documents.status).not.toHaveBeenCalled()
  })
  it('does not offline a document when confirmation is canceled', async () => {
    await open(DocumentsPage, '/admin/documents')
    await button('下架').trigger('click')
    await confirm('取消')
    expect(api.documents.offline).not.toHaveBeenCalled()
  })
  it('polls pending status while visible and stops after leaving the page', async () => {
    vi.useFakeTimers()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    const queued = { ...version, id: 12, versionNumber: 2, status: 'QUEUED' as const }
    api.documents.status.mockResolvedValue(
      ok({ documentId: 1, status: 'ACTIVE', currentVersion: version, latestVersion: queued })
    )
    await open(VersionState, '/admin/documents', {
      document: { ...documentFixture, latestVersion: queued }
    })
    expect(api.documents.status).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(2100)
    expect(api.documents.status).toHaveBeenCalledTimes(2)
    wrapper!.unmount()
    wrapper = undefined
    await vi.advanceTimersByTimeAsync(5000)
    expect(api.documents.status).toHaveBeenCalledTimes(2)
  })
})
describe('question, snapshot and personal interfaces', () => {
  it('returns to the preceding favorites page when its last item is removed', async () => {
    const ui = await open(HistoryPage, '/favorites', {}, 'USER')
    api.qa.favorites
      .mockResolvedValueOnce(ok(page([{ ...answer, favorited: true }], 11)))
      .mockResolvedValueOnce(ok(page([], 10)))
      .mockResolvedValue(ok(page([{ ...answer, qaId: 30, favorited: true }], 10)))
    ui.findComponent(TablePager).vm.$emit('update:currentPage', 2)
    await flushPromises()
    await button('取消收藏').trigger('click')
    await flushPromises()
    expect(api.qa.favorites).toHaveBeenLastCalledWith({ page: 1, pageSize: 10 })
  })
  it('only provides an explicit retry for a retryable failure', async () => {
    const ui = await open(QaResult, '/qa', {
      value: {
        ...answer,
        status: 'FAILED',
        answer: null,
        sources: [],
        retryable: true,
        errorCode: 'MODEL_TIMEOUT',
        errorMessage: '模型超时'
      }
    })
    await button('重新提问').trigger('click')
    expect(ui.emitted('retry')).toEqual([['什么是需求？']])
    await ui.setProps({
      value: {
        ...answer,
        status: 'FAILED',
        answer: null,
        sources: [],
        retryable: false,
        errorCode: 'MODEL_NOT_CONFIGURED',
        errorMessage: '尚未配置'
      }
    })
    expect(ui.text()).not.toContain('重新提问')
    expect(api.qa.ask).not.toHaveBeenCalled()
  })
  it('generates a study outline from the selected document and exposes its actual sources', async () => {
    const ui = await open(StudyPage, '/study', {}, 'USER')
    await ui.get('[data-testid=study-form]').trigger('submit')
    expect(api.study.generate).not.toHaveBeenCalled()
    await ui.findComponent(ElSelect).setValue(1)
    await ui.get('[data-testid=study-form]').trigger('submit')
    await flushPromises()
    expect(api.study.generate).toHaveBeenCalledWith(1, 'OUTLINE', undefined)
    expect(ui.text()).toContain('当时的资料标题')
    expect(ui.text()).toContain('已保存在我的历史')
  })
  it('generates the selected exercise count and prevents duplicate pending requests', async () => {
    let resolve!: (value: ApiResponse<unknown>) => void
    api.study.generate.mockReturnValue(
      new Promise(done => {
        resolve = done
      })
    )
    const ui = await open(StudyPage, '/study', {}, 'USER')
    await ui.findComponent(ElSelect).setValue(1)
    await ui.findComponent(ElRadioGroup).setValue('EXERCISES')
    await ui.get('[data-testid=study-form]').trigger('submit')
    await ui.get('[data-testid=study-form]').trigger('submit')
    expect(api.study.generate).toHaveBeenCalledExactlyOnceWith(1, 'EXERCISES', 3)
    resolve(
      ok({
        kind: 'EXERCISES',
        documentId: 1,
        selectedChunkCount: 1,
        totalChunkCount: 1,
        result: answer
      })
    )
    await flushPromises()
    expect(ui.text()).toContain('回答成功')
  })
  it('shows a generation failure and does not retain the previous generated answer', async () => {
    const ui = await open(StudyPage, '/study', {}, 'USER')
    await ui.findComponent(ElSelect).setValue(1)
    await ui.get('[data-testid=study-form]').trigger('submit')
    await flushPromises()
    api.study.generate.mockRejectedValue(new Error('生成连接失败'))
    await ui.get('[data-testid=study-form]').trigger('submit')
    await flushPromises()
    expect(ui.text()).toContain('生成连接失败')
    expect(ui.findComponent(QaResult).exists()).toBe(false)
  })
  it('appends follow-ups with a parent and starts a separate conversation on request', async () => {
    const ui = await open(QaPage, '/qa', {}, 'USER')
    await ui.get('[data-testid=qa-form] textarea').setValue('什么是需求？')
    await ui.get('[data-testid=qa-form]').trigger('submit')
    await flushPromises()
    api.qa.ask.mockResolvedValue(
      ok({ ...answer, qaId: 32, parentQaId: 31, question: '能举个例子吗？' })
    )
    await ui.get('[data-testid=qa-form] textarea').setValue('能举个例子吗？')
    await ui.get('[data-testid=qa-form]').trigger('submit')
    await flushPromises()
    expect(api.qa.ask).toHaveBeenLastCalledWith('能举个例子吗？', 31)
    expect(ui.findAllComponents(QaResult)).toHaveLength(2)
    await ui
      .findAll('button')
      .find(button => button.text() === '开始新对话')!
      .trigger('click')
    await flushPromises()
    expect(ui.findAllComponents(QaResult)).toHaveLength(0)
    await ui.get('[data-testid=qa-form] textarea').setValue('全新的问题')
    await ui.get('[data-testid=qa-form]').trigger('submit')
    await flushPromises()
    expect(api.qa.ask).toHaveBeenLastCalledWith('全新的问题')
  })
  it('retries a history entry within that entry conversation rather than an unrelated cached chat', async () => {
    const ui = await open(HistoryDetail, '/history/31', {}, 'USER')
    await flushPromises()
    ui.findComponent(QaResult).vm.$emit('retry', '重试原问题')
    await flushPromises()
    expect(router!.currentRoute.value.path).toBe('/qa')
    expect(router!.currentRoute.value.query.turn).toBe('31')
    expect(useDraftStore().question).toBe('重试原问题')
  })
  it('restores a saved conversation by URL before accepting a follow-up', async () => {
    const ui = await open(QaPage, '/qa?turn=31', {}, 'USER')
    await flushPromises()
    expect(api.qa.conversation).toHaveBeenCalledWith(31)
    expect(ui.findAllComponents(QaResult)).toHaveLength(1)
    await ui.get('[data-testid=qa-form] textarea').setValue('它有什么作用？')
    await ui.get('[data-testid=qa-form]').trigger('submit')
    await flushPromises()
    expect(api.qa.ask).toHaveBeenLastCalledWith('它有什么作用？', 31)
  })
  it('blocks follow-ups after a conversation cannot be loaded and permits a new conversation', async () => {
    api.qa.conversation.mockRejectedValue(new Error('资源不存在'))
    const ui = await open(QaPage, '/qa?turn=99', {}, 'USER')
    await flushPromises()
    await ui.get('[data-testid=qa-form]').trigger('submit')
    expect(api.qa.ask).not.toHaveBeenCalled()
    expect(ui.text()).toContain('资源不存在')
    await ui
      .findAll('button')
      .find(button => button.text() === '开始新对话')!
      .trigger('click')
    await flushPromises()
    expect(ui.text()).not.toContain('资源不存在')
  })
  it('prevents blank/overlong questions and duplicate pending submissions', async () => {
    let resolve!: (value: ApiResponse<QaDetail>) => void
    api.qa.ask.mockReturnValue(
      new Promise(done => {
        resolve = done
      })
    )
    const ui = await open(QaPage, '/qa', {}, 'USER')
    await ui.get('[data-testid=qa-form]').trigger('submit')
    expect(api.qa.ask).not.toHaveBeenCalled()
    await ui.get('[data-testid=qa-form] textarea').setValue('😀'.repeat(501))
    await ui.get('[data-testid=qa-form]').trigger('submit')
    expect(api.qa.ask).not.toHaveBeenCalled()
    await ui.get('[data-testid=qa-form] textarea').setValue(' 什么是需求？ ')
    await ui.get('[data-testid=qa-form]').trigger('submit')
    await ui.get('[data-testid=qa-form]').trigger('submit')
    expect(api.qa.ask).toHaveBeenCalledExactlyOnceWith('什么是需求？')
    resolve(ok(answer))
    await flushPromises()
    expect(ui.text()).toContain('回答成功')
    expect(useDraftStore().question).toBe('')
  })
  it('keeps previous conversation turns when a follow-up request fails, preserving the new question', async () => {
    const ui = await open(QaPage, '/qa', {}, 'USER')
    await ui.get('[data-testid=qa-form] textarea').setValue('什么是需求？')
    await ui.get('[data-testid=qa-form]').trigger('submit')
    await flushPromises()
    expect(ui.text()).toContain('当时的资料标题')
    api.qa.ask.mockRejectedValue(
      new ApiError('请求超时，请先查看历史记录', null, null, null, 'timeout')
    )
    await ui.get('[data-testid=qa-form] textarea').setValue('另一个问题')
    await ui.get('[data-testid=qa-form]').trigger('submit')
    await flushPromises()
    expect(ui.text()).toContain('当时的资料标题')
    expect(ui.text()).toContain('请先查看历史记录')
    expect(useDraftStore().question).toBe('另一个问题')
  })
  it.each(['NO_EVIDENCE', 'FAILED'] as const)(
    'never exposes success actions for %s',
    async status => {
      const ui = await open(QaResult, '/qa', {
        value: {
          ...answer,
          status,
          answer: null,
          sources: [],
          errorCode: status === 'FAILED' ? 'MODEL_NOT_CONFIGURED' : 'EVIDENCE_INSUFFICIENT',
          errorMessage: '受控失败'
        }
      })
      expect(ui.text()).not.toContain('收藏回答')
      expect(ui.text()).not.toContain('保存反馈')
      expect(ui.text()).not.toContain('回答来源')
      if (status === 'FAILED') expect(ui.text()).toContain('联系课程管理员')
    }
  )
  it.each(['UPDATED', 'OFFLINE'] as const)(
    'loads a %s source only through the owner snapshot endpoint and escapes source HTML',
    async availability => {
      api.qa.source.mockResolvedValue(
        ok({ ...source, availability, excerpt: '<img src=x onerror=alert(1)>saved original' })
      )
      const ui = await open(SourceList, '/history/31', {
        qaId: 31,
        sources: [{ ...source, availability }]
      })
      await ui.get('button.source-card').trigger('click')
      await flushPromises()
      expect(api.qa.source).toHaveBeenCalledExactlyOnceWith(31, 51)
      expect(api.documents.chunks).not.toHaveBeenCalled()
      expect(ui.text()).toContain('<img src=x onerror=alert(1)>saved original')
      expect(ui.find('img').exists()).toBe(false)
      expect(ui.text()).toContain('块 1')
    }
  )
  it('loads saved feedback, enforces 500 code points and keeps an unsaved reason after failure', async () => {
    const value = {
      ...answer,
      feedback: { id: 61, qaId: 31, rating: 'HELPFUL' as const, reason: '已保存', updatedAt: now }
    }
    const ui = await open(QaResult, '/history/31', { value })
    expect((ui.get('textarea').element as HTMLTextAreaElement).value).toBe('已保存')
    await ui.get('textarea').setValue('😀'.repeat(501))
    await ui.get('form').trigger('submit')
    expect(api.qa.feedback).not.toHaveBeenCalled()
    await ui.get('textarea').setValue('未保存的新反馈')
    api.qa.feedback.mockRejectedValue(new ApiError('反馈保存失败', 422, 42201, 'fixture', 'http'))
    await ui.get('form').trigger('submit')
    await flushPromises()
    expect((ui.get('textarea').element as HTMLTextAreaElement).value).toBe('未保存的新反馈')
    expect(ui.text()).toContain('反馈保存失败')
    await ui.setProps({ value: { ...value, favorited: true } })
    expect((ui.get('textarea').element as HTMLTextAreaElement).value).toBe('未保存的新反馈')
  })
  it('does not send duplicate favorites or invent a saved state on failure', async () => {
    let reject!: (error: unknown) => void
    api.qa.favorite.mockReturnValue(
      new Promise((_done, fail) => {
        reject = fail
      })
    )
    const ui = await open(QaResult, '/history/31', { value: answer })
    await button('收藏回答').trigger('click')
    await button('收藏回答').trigger('click')
    expect(api.qa.favorite).toHaveBeenCalledTimes(1)
    reject(new ApiError('收藏失败', 422, 42201, 'fixture', 'http'))
    await flushPromises()
    expect(ui.text()).toContain('收藏回答')
    expect(ui.text()).not.toContain('取消收藏')
  })
  it('shows all three history states and only requests current-user filters', async () => {
    const ui = await open(HistoryPage, '/history', {}, 'USER')
    expect(ui.text()).toContain('回答成功')
    expect(ui.text()).toContain('依据不足')
    expect(ui.text()).toContain('问答失败')
    expect(api.qa.history).toHaveBeenCalledWith({ page: 1, pageSize: 10, status: undefined })
  })
})
describe('administrative interfaces', () => {
  it('refreshes statistics when the user submits unchanged date filters', async () => {
    const ui = await open(StatsPage, '/admin/stats')
    expect(api.admin.stats).toHaveBeenCalledTimes(1)
    await ui.get('form').trigger('submit')
    await flushPromises()
    expect(api.admin.stats).toHaveBeenCalledTimes(2)
  })
  it('applies management feedback date/rating filters and starts at page one', async () => {
    const ui = await open(FeedbackPage, '/admin/feedback')
    ui.findComponent(ElSelect).vm.$emit('update:modelValue', 'HELPFUL')
    const pickers = ui.findAllComponents(ElDatePicker)
    pickers[0].vm.$emit('update:modelValue', '2026-10-28')
    pickers[1].vm.$emit('update:modelValue', '2026-10-29')
    await ui.get('form').trigger('submit')
    await flushPromises()
    expect(api.admin.feedback).toHaveBeenLastCalledWith({
      rating: 'HELPFUL',
      from: '2026-10-28',
      to: '2026-10-29',
      page: 1,
      pageSize: 10
    })
  })
  it('only shows a feedback as saved after the returned server value arrives', async () => {
    const ui = await open(QaResult, '/history/31', { value: answer })
    ui.findComponent(ElRadioGroup).vm.$emit('update:modelValue', 'HELPFUL')
    await ui.get('textarea').setValue('来源清楚')
    await ui.get('form').trigger('submit')
    await flushPromises()
    expect(api.qa.feedback).toHaveBeenCalledExactlyOnceWith(31, 'HELPFUL', '来源清楚')
    expect(ui.text()).toContain('已保存：有帮助')
  })
  it('retains a referenced category when the server refuses its deletion', async () => {
    api.categories.remove.mockRejectedValue(
      new ApiError('分类仍被资料引用', 409, 40903, 'fixture', 'http')
    )
    const ui = await open(CategoriesPage, '/admin/categories')
    await button('删除').trigger('click')
    await confirm('删除')
    expect(ui.text()).toContain('分类仍被资料引用')
    expect(ui.text()).toContain('讲义')
  })
  it('keeps the last administrator enabled after a rejected change', async () => {
    api.admin.setUserStatus.mockRejectedValue(
      new ApiError('不能停用最后一个有效管理员', 409, 40905, 'fixture', 'http')
    )
    const ui = await open(UsersPage, '/admin/users')
    await button('停用').trigger('click')
    await confirm('确认')
    expect(ui.text()).toContain('最后一个有效管理员')
    expect(ui.text()).toContain('正常')
    expect(useAuthStore().isLoggedIn).toBe(true)
  })
  it('renders zero statistics and rejects inverted local dates without requesting', async () => {
    const ui = await open(StatsPage, '/admin/stats')
    expect(ui.findAll('.stat-card strong')).toHaveLength(7)
    expect(ui.findAll('.stat-card strong').every(item => item.text() === '0')).toBe(true)
    const pickers = ui.findAllComponents(ElDatePicker)
    pickers[0].vm.$emit('update:modelValue', '2026-11-02')
    pickers[1].vm.$emit('update:modelValue', '2026-10-29')
    await ui.get('form').trigger('submit')
    await flushPromises()
    expect(api.admin.stats).toHaveBeenCalledTimes(1)
    expect(ui.text()).toContain('开始日期不能晚于结束日期')
    pickers[0].vm.$emit('update:modelValue', '2026-10-28')
    await ui.get('form').trigger('submit')
    await flushPromises()
    expect(api.admin.stats).toHaveBeenLastCalledWith({ from: '2026-10-28', to: '2026-10-29' })
    expect(ui.text()).toContain('包含下架与解析失败记录')
  })
  it('queries management feedback without entering someone else’s personal detail', async () => {
    api.admin.feedback.mockResolvedValue(
      ok(
        page([
          {
            id: 61,
            qaId: 31,
            rating: 'UNHELPFUL',
            reason: '测试原因',
            updatedAt: now,
            userId: 8,
            username: 'student_b',
            question: '他人的问题'
          }
        ])
      )
    )
    const ui = await open(FeedbackPage, '/admin/feedback')
    expect(ui.text()).toContain('他人的问题')
    expect(ui.text()).toContain('student_b')
    expect(api.qa.detail).not.toHaveBeenCalled()
  })
})
