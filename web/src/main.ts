import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin } from '@tanstack/vue-query'
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/message/style/css'
import App from './App.vue'
import router from './router'
import { queryClient } from './lib/query-client'
import { initTheme } from './stores/theme'
import './styles/index.scss'
import { useAuthStore } from './stores/auth'
import { loginDestination } from './router/access'

initTheme()

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(VueQueryPlugin, { queryClient })
app.use(router)
app.mount('#app')

// Another tab can change localStorage credentials. Hide this tab's private state immediately.
window.addEventListener('storage', async event => {
  if (event.storageArea !== localStorage || (event.key !== 'kb_token' && event.key !== null)) return
  const auth = useAuthStore(pinia)
  if (!auth.syncStoredSession()) return
  try {
    const valid = await auth.ensureSession()
    await router.replace(
      valid && auth.user
        ? loginDestination(router, router.currentRoute.value.fullPath, auth.user.role)
        : '/login'
    )
  } catch {
    /* App presents the recoverable identity verification failure. */
  }
})
