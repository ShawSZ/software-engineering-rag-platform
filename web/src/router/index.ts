import { createRouter, createWebHistory } from 'vue-router'
import { courseRoutes } from './course-routes'
import { installAccessGuard } from './access'
const router = createRouter({
  history: createWebHistory(import.meta.env.VITE_ROUTER_BASE || '/'),
  routes: courseRoutes
})
installAccessGuard(router)
export default router
