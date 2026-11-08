const prefetched = new Set<string>()
export const viewImports: Record<string, () => Promise<unknown>> = {
  '/documents': () => import('@/views/course/DocumentsPage.vue'),
  '/qa': () => import('@/views/course/QaPage.vue'),
  '/study': () => import('@/views/course/StudyPage.vue'),
  '/history': () => import('@/views/course/HistoryPage.vue'),
  '/favorites': () => import('@/views/course/HistoryPage.vue'),
  '/admin/documents': () => import('@/views/course/DocumentsPage.vue'),
  '/admin/categories': () => import('@/views/course/CategoriesPage.vue'),
  '/admin/users': () => import('@/views/course/UsersPage.vue'),
  '/admin/feedback': () => import('@/views/course/FeedbackPage.vue'),
  '/admin/stats': () => import('@/views/course/StatsPage.vue')
}
function preload(key: string, loader: () => Promise<unknown>) {
  if (prefetched.has(key)) return
  prefetched.add(key)
  void loader().catch(() => prefetched.delete(key))
}
export function prefetchView(path: string) {
  if (viewImports[path]) preload(path, viewImports[path])
}
export function prefetchMainLayout() {
  preload('__main_layout__', () => import('@/layouts/MainLayout.vue'))
}
export function prefetchAfterLogin(target: string) {
  prefetchMainLayout()
  prefetchView(target.split('?')[0])
}
export function prefetchAllViewsOnIdle(paths: string[]) {
  const run = () => paths.forEach(prefetchView)
  if (typeof requestIdleCallback !== 'undefined') requestIdleCallback(run, { timeout: 3000 })
  else setTimeout(run, 1500)
}
