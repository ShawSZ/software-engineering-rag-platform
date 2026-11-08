import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getToken, setToken, clearAuth, setUser } from '@/utils/auth'
import { courseApi, type LoginResult, type User } from '@/api/course'
import { queryClient } from '@/lib/query-client'
import { useDraftStore } from './drafts'
import { ApiError } from '@/utils/http-client'

type Identity = Pick<User, 'userId' | 'username' | 'displayName' | 'role'>
type SessionState = 'unknown' | 'checking' | 'authenticated' | 'anonymous' | 'error'
export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken())
  // Persisted roles never render protected content before /auth/me.
  const user = ref<Identity | null>(null)
  const state = ref<SessionState>(token.value ? 'unknown' : 'anonymous')
  const restoreError = ref('')
  const pendingPath = ref('/documents')
  const sessionRevision = ref(0)
  const drafts = useDraftStore()
  let restoring: Promise<boolean> | null = null
  let signingIn: Promise<LoginResult> | null = null
  const loginPending = ref(false)
  const isLoggedIn = computed(
    () => state.value === 'authenticated' && !!token.value && !!user.value
  )
  const isAdmin = computed(() => isLoggedIn.value && user.value?.role === 'ADMIN')
  const isInitializing = computed(
    () => !loginPending.value && (state.value === 'unknown' || state.value === 'checking')
  )
  function clearSession(reason: 'logout' | 'expired' = 'logout') {
    if (reason === 'expired') drafts.suspend()
    else drafts.clear()
    token.value = null
    user.value = null
    state.value = 'anonymous'
    restoreError.value = ''
    clearAuth()
    queryClient.clear()
    sessionRevision.value++
    restoring = null
  }
  function acceptIdentity(identity: Identity) {
    const safe = {
      userId: identity.userId,
      username: identity.username,
      displayName: identity.displayName,
      role: identity.role
    }
    user.value = safe
    setUser(safe)
    drafts.activate(safe.userId)
    state.value = 'authenticated'
    restoreError.value = ''
  }
  function applySession(data: LoginResult) {
    queryClient.clear()
    sessionRevision.value++
    token.value = data.token
    setToken(data.token)
    acceptIdentity(data)
  }
  async function ensureSession(): Promise<boolean> {
    if (isLoggedIn.value) return true
    if (!token.value) return false
    if (restoring) return restoring
    const expectedToken = token.value
    state.value = 'checking'
    restoreError.value = ''
    const task = (async () => {
      try {
        const result = await courseApi.auth.me()
        if (token.value !== expectedToken) return false
        if (result.data.status !== 'ENABLED') {
          clearSession('expired')
          return false
        }
        acceptIdentity(result.data)
        return true
      } catch (error: unknown) {
        if (token.value !== expectedToken) return false
        if (error instanceof ApiError && error.status === 401) {
          clearSession('expired')
          return false
        }
        user.value = null
        state.value = 'error'
        restoreError.value = error instanceof Error ? error.message : '身份验证失败，请重试。'
        throw error
      } finally {
        if (token.value === expectedToken) restoring = null
      }
    })()
    restoring = task
    return task
  }
  function login(username: string, password: string): Promise<LoginResult> {
    if (signingIn) return signingIn
    loginPending.value = true
    const revision = sessionRevision.value
    signingIn = (async () => {
      const result = await courseApi.auth.login(username, password)
      if (revision !== sessionRevision.value) throw new Error('会话已变化，请重新登录。')
      queryClient.clear()
      sessionRevision.value++
      user.value = null
      token.value = result.data.token
      setToken(result.data.token)
      state.value = 'unknown'
      restoring = null
      if (!(await ensureSession())) throw new Error('登录身份已失效，请重新登录。')
      const identity = readIdentity()
      if (!identity) throw new Error('登录身份已失效，请重新登录。')
      return { token: result.data.token, ...identity }
    })().finally(() => {
      signingIn = null
      loginPending.value = false
    })
    return signingIn
  }
  function readIdentity(): Identity | null {
    return user.value
  }
  async function logout() {
    const expectedToken = token.value
    try {
      if (expectedToken) await courseApi.auth.logout()
    } finally {
      if (token.value === expectedToken) clearSession()
    }
  }
  function syncStoredSession() {
    const stored = getToken()
    if (stored === token.value) return false
    drafts.clear()
    queryClient.clear()
    user.value = null
    token.value = stored
    state.value = stored ? 'unknown' : 'anonymous'
    restoreError.value = ''
    restoring = null
    sessionRevision.value++
    return true
  }
  return {
    token,
    user,
    state,
    isLoggedIn,
    isAdmin,
    isInitializing,
    restoreError,
    pendingPath,
    sessionRevision,
    ensureSession,
    login,
    logout,
    clearSession,
    applySession,
    syncStoredSession
  }
})
