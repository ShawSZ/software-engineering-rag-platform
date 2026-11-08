import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { VueQueryPlugin } from '@tanstack/vue-query'
import { queryClient } from '@/lib/query-client'
import App from '@/App.vue'
import { courseRoutes } from '@/router/course-routes'
import { installAccessGuard } from '@/router/access'
import { useAuthStore } from '@/stores/auth'
import { useDraftStore } from '@/stores/drafts'
import type { ApiResponse, LoginResult, QaDetail } from '@/api/course-types'

const api = vi.hoisted(() => ({ login: vi.fn(), register: vi.fn(), logout: vi.fn(), me: vi.fn() }))
const askApi = vi.hoisted(() => vi.fn())
const conversationApi = vi.hoisted(() => vi.fn())
vi.mock('@/api/course', () => ({
  courseApi: {
    auth: api,
    qa: {
      ask: askApi,
      conversation: conversationApi,
      history: async () => ({ data: { items: [], total: 0, page: 1, pageSize: 10 } })
    },
    categories: { list: async () => ({ data: [] }) },
    documents: {
      list: async () => ({ data: { items: [], total: 0, page: 1, pageSize: 10 } }),
      adminList: async () => ({ data: { items: [], total: 0, page: 1, pageSize: 10 } })
    }
  }
}))
vi.mock('@/router/prefetch', () => ({
  prefetchAfterLogin: vi.fn(),
  prefetchAllViewsOnIdle: vi.fn(),
  prefetchView: vi.fn()
}))
const student: LoginResult = {
  token: 'ui-synthetic-token',
  userId: 41,
  username: 'student_ui',
  displayName: '测试学生',
  role: 'USER'
}
function ok<T>(data: T): ApiResponse<T> {
  return { code: 0, message: 'success', data, requestId: 'ui-fixture' }
}
let wrapper: VueWrapper | undefined
let router: Router
beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  vi.resetAllMocks()
  api.me.mockResolvedValue(
    ok({ ...student, status: 'ENABLED', createdAt: '2026-10-29T12:00:00+08:00' })
  )
  queryClient.clear()
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  )
  setActivePinia(createPinia())
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  queryClient.clear()
  vi.unstubAllGlobals()
  setActivePinia(undefined)
})
async function open(path: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  router = createRouter({ history: createMemoryHistory(), routes: courseRoutes })
  installAccessGuard(router)
  wrapper = mount(App, {
    global: { plugins: [pinia, router, ElementPlus, [VueQueryPlugin, { queryClient }]] }
  })
  await router.push(path)
  await flushPromises()
  return wrapper
}
async function fill(register = false) {
  await wrapper!.get('[data-testid="account-username"]').setValue('student_ui')
  await wrapper!.get('[data-testid="account-password"]').setValue('  pass123  ')
  if (register) await wrapper!.get('[data-testid="account-confirmation"]').setValue('  pass123  ')
}
describe('course account interface', () => {
  it('keeps the same QA page when its saved turn URL changes and sends the next parent ID', async () => {
    api.login.mockResolvedValue(ok(student))
    const result: QaDetail = {
      qaId: 201,
      question: '第一个问题',
      status: 'NO_EVIDENCE',
      answer: null,
      sources: [],
      latencyMs: 1,
      errorCode: 'EVIDENCE_INSUFFICIENT',
      errorMessage: '依据不足',
      retryable: false,
      createdAt: '2026-11-01T12:00:00+08:00',
      favorited: false,
      feedback: null
    }
    askApi
      .mockResolvedValueOnce(ok(result))
      .mockResolvedValueOnce(ok({ ...result, qaId: 202, parentQaId: 201, question: '继续追问' }))
    conversationApi.mockResolvedValue(ok([result]))
    const ui = await open('/login')
    await fill()
    await ui.get('form').trigger('submit')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/documents'))
    await router.push('/qa')
    await flushPromises()
    await ui.get('[data-testid="question-draft"]').setValue('第一个问题')
    await ui.get('[data-testid="qa-form"]').trigger('submit')
    await flushPromises()
    await vi.waitFor(() => expect(router.currentRoute.value.query.turn).toBe('201'))
    expect(conversationApi).not.toHaveBeenCalled()
    await ui.get('[data-testid="question-draft"]').setValue('继续追问')
    await ui.get('[data-testid="qa-form"]').trigger('submit')
    await flushPromises()
    expect(askApi).toHaveBeenLastCalledWith('继续追问', 201)
    expect(ui.text()).toContain('本次对话 2 轮')
  })
  it('keeps a pending question locked when leaving and returning to the QA page', async () => {
    api.login.mockResolvedValue(ok(student))
    let resolve!: (value: ApiResponse<QaDetail>) => void
    askApi.mockReturnValue(
      new Promise<ApiResponse<QaDetail>>(done => {
        resolve = done
      })
    )
    const ui = await open('/login')
    await fill()
    await ui.get('form').trigger('submit')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/documents'))
    await router.push('/qa')
    await flushPromises()
    await ui.get('[data-testid="question-draft"]').setValue('仅提交一次的问题')
    await ui.get('form').trigger('submit')
    await router.push('/history')
    await flushPromises()
    await router.push('/qa')
    await flushPromises()
    await ui.get('form').trigger('submit')
    resolve(
      ok({
        qaId: 101,
        question: '仅提交一次的问题',
        status: 'NO_EVIDENCE',
        answer: null,
        sources: [],
        latencyMs: 10,
        errorCode: 'EVIDENCE_INSUFFICIENT',
        errorMessage: '依据不足',
        retryable: false,
        createdAt: '2026-10-29T12:00:00+08:00',
        favorited: false,
        feedback: null
      })
    )
    await flushPromises()
    expect(askApi).toHaveBeenCalledTimes(1)
  })
  it('registers only student fields and returns to login without retaining passwords', async () => {
    api.register.mockResolvedValue(ok({ userId: 41 }))
    const ui = await open('/register')
    await fill(true)
    await ui.get('[data-testid="account-display-name"]').setValue(' 测试学生 ')
    await ui.get('form').trigger('submit')
    await flushPromises()
    expect(api.register).toHaveBeenCalledExactlyOnceWith({
      username: 'student_ui',
      password: '  pass123  ',
      displayName: '测试学生'
    })
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect((ui.get('[data-testid="account-username"]').element as HTMLInputElement).value).toBe(
      'student_ui'
    )
    expect((ui.get('[data-testid="account-password"]').element as HTMLInputElement).value).toBe('')
    expect(ui.text()).not.toContain('默认账号')
    expect(ui.text()).not.toContain('LDAP')
    expect(JSON.stringify(localStorage) + JSON.stringify(sessionStorage)).not.toContain('pass123')
  })
  it('shows duplicate username in the registration form without navigating or accepting a session', async () => {
    api.register.mockRejectedValue(new Error('用户名已存在'))
    const ui = await open('/register')
    await fill(true)
    await ui.get('form').trigger('submit')
    await flushPromises()
    expect(ui.get('[data-testid="account-error"]').text()).toContain('用户名已存在')
    expect(router.currentRoute.value.path).toBe('/register')
    expect(useAuthStore().isLoggedIn).toBe(false)
  })
  it('rejects confirmation mismatch without a server request', async () => {
    const ui = await open('/register')
    await fill(true)
    await ui.get('[data-testid="account-confirmation"]').setValue('different')
    await ui.get('form').trigger('submit')
    await flushPromises()
    expect(api.register).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(ui.text()).toContain('两次密码不一致'))
  })
  it('logs in through the real root composition and rechecks admin return-path permissions', async () => {
    api.login.mockResolvedValue(ok(student))
    const ui = await open('/login?redirect=/admin/users')
    await fill()
    await ui.get('form').trigger('submit')
    await flushPromises()
    expect(api.login).toHaveBeenCalledExactlyOnceWith('student_ui', '  pass123  ')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/403'))
    expect(ui.text()).toContain('当前账号没有访问此页面的权限')
    expect(ui.text()).not.toContain('分类管理')
  })
  it('handles wrong credentials inline and suppresses repeated pending submissions', async () => {
    let reject!: (error: Error) => void
    api.login.mockReturnValue(
      new Promise((_done, fail) => {
        reject = fail
      })
    )
    const ui = await open('/login')
    await fill()
    await ui.get('form').trigger('submit')
    await ui.get('form').trigger('submit')
    expect(api.login).toHaveBeenCalledTimes(1)
    reject(new Error('用户名或密码错误'))
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(ui.get('[data-testid="account-error"]').text()).toContain('用户名或密码错误')
    expect(useAuthStore().isLoggedIn).toBe(false)
  })
  it('renders verified administrator menus without requesting enterprise configuration', async () => {
    api.login.mockResolvedValue(ok({ ...student, role: 'ADMIN' }))
    api.me.mockResolvedValue(ok({ ...student, role: 'ADMIN', status: 'ENABLED' }))
    const ui = await open('/login')
    await fill()
    await ui.get('form').trigger('submit')
    await flushPromises()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/documents'))
    for (const label of [
      '资料库',
      '智能问答',
      '我的历史',
      '我的收藏',
      '资料管理',
      '分类管理',
      '用户管理',
      '用户反馈',
      '统计概览'
    ])
      expect(ui.text()).toContain(label)
    for (const label of ['知识库', '图片生成', '大模型配置', 'API Key', '初始配置'])
      expect(ui.text()).not.toContain(label)
    expect(api.me).toHaveBeenCalledTimes(1)
  })
  it('removes cached private component state on expiration and restores only same-user draft', async () => {
    api.login.mockResolvedValue(ok(student))
    const ui = await open('/login')
    await fill()
    await ui.get('form').trigger('submit')
    await flushPromises()
    await router.push('/qa')
    await flushPromises()
    await ui.get('[data-testid="question-draft"]').setValue('仅学生甲的问题')
    // Leaving the question page must never make the next account inherit its private state.
    await router.push('/documents')
    await flushPromises()
    useAuthStore().clearSession('expired')
    await router.push('/login')
    await flushPromises()
    expect(ui.find('[data-testid="question-draft"]').exists()).toBe(false)
    await fill()
    await ui.get('form').trigger('submit')
    await flushPromises()
    await router.push('/qa')
    await flushPromises()
    expect((ui.get('[data-testid="question-draft"]').element as HTMLTextAreaElement).value).toBe(
      '仅学生甲的问题'
    )
    await router.push('/login')
    await flushPromises()
    api.login.mockResolvedValue(
      ok({ ...student, userId: 42, username: 'student_b', token: 'ui-b' })
    )
    api.me.mockResolvedValue(
      ok({ ...student, userId: 42, username: 'student_b', status: 'ENABLED' })
    )
    await fill()
    await ui.get('form').trigger('submit')
    await flushPromises()
    await router.push('/qa')
    await flushPromises()
    expect((ui.get('[data-testid="question-draft"]').element as HTMLTextAreaElement).value).toBe('')
    expect(useDraftStore().owner).toBe(42)
  })
  it('never flashes an administrator page while a persisted identity is being verified', async () => {
    localStorage.setItem('kb_token', 'ui-synthetic-token')
    localStorage.setItem('kb_user', JSON.stringify({ ...student, role: 'ADMIN' }))
    let resolve!: (data: unknown) => void
    api.me.mockReturnValue(
      new Promise(done => {
        resolve = done
      })
    )
    const pinia = createPinia()
    setActivePinia(pinia)
    router = createRouter({ history: createMemoryHistory(), routes: courseRoutes })
    installAccessGuard(router)
    wrapper = mount(App, {
      global: { plugins: [pinia, router, ElementPlus, [VueQueryPlugin, { queryClient }]] }
    })
    const pending = router.push('/admin/users')
    await vi.waitFor(() => expect(api.me).toHaveBeenCalled())
    expect(wrapper.text()).toContain('正在验证登录身份')
    expect(wrapper.text()).not.toContain('用户管理')
    resolve(ok({ ...student, status: 'ENABLED' }))
    await pending
    await flushPromises()
    expect(wrapper.text()).toContain('403')
    expect(wrapper.text()).not.toContain('分类管理')
  })
  it('keeps the login shell recoverable after /me fails and retries verification explicitly', async () => {
    localStorage.setItem('kb_token', 'ui-synthetic-token')
    api.me
      .mockRejectedValueOnce(new Error('网络中断'))
      .mockResolvedValueOnce(ok({ ...student, status: 'ENABLED' }))
    const pinia = createPinia()
    setActivePinia(pinia)
    router = createRouter({ history: createMemoryHistory(), routes: courseRoutes })
    installAccessGuard(router)
    wrapper = mount(App, {
      global: { plugins: [pinia, router, ElementPlus, [VueQueryPlugin, { queryClient }]] }
    })
    await router.push('/documents')
    await flushPromises()
    expect(wrapper.text()).toContain('暂时无法验证登录身份')
    expect(wrapper.find('[data-testid="title-draft"]').exists()).toBe(false)
    const retry = wrapper.findAll('button').find(button => button.text() === '重新验证')!
    await retry.trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/documents'))
    expect(wrapper.text()).toContain('标题')
  })
  it('cancels logout without requesting, then clears all drafts after confirmed logout', async () => {
    api.login.mockResolvedValue(ok(student))
    api.logout.mockResolvedValue(ok(null))
    const ui = await open('/login')
    await fill()
    await ui.get('form').trigger('submit')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/documents'))
    useDraftStore().question = 'logout draft'
    const logout = () => ui.findAll('button').find(button => button.text() === '退出')!
    const dialogButton = (text: string) =>
      Array.from(document.querySelectorAll<HTMLButtonElement>('.el-message-box button'))
        .reverse()
        .find(button => button.textContent?.trim() === text)
    await logout().trigger('click')
    await vi.waitFor(() => expect(dialogButton('取消')).toBeTruthy())
    dialogButton('取消')!.click()
    await flushPromises()
    expect(api.logout).not.toHaveBeenCalled()
    expect(useAuthStore().isLoggedIn).toBe(true)
    await logout().trigger('click')
    await vi.waitFor(() => expect(dialogButton('退出')).toBeTruthy())
    dialogButton('退出')!.click()
    await vi.waitFor(() => expect(api.logout).toHaveBeenCalledTimes(1))
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(api.logout).toHaveBeenCalledTimes(1)
    expect(sessionStorage.length).toBe(0)
    ElMessageBox.close()
  })
})
