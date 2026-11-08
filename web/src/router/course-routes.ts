import type { RouteRecordRaw } from 'vue-router'
const documents = () => import('@/views/course/DocumentsPage.vue')
export const courseRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    component: () => import('@/views/login/index.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/register',
    component: () => import('@/views/register/index.vue'),
    meta: { public: true, title: '学生注册' }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/documents',
    children: [
      { path: 'documents', component: documents, meta: { title: '资料库' } },
      {
        path: 'documents/:id(\\d+)',
        component: () => import('@/views/course/DocumentDetail.vue'),
        meta: { title: '资料详情' }
      },
      {
        path: 'qa',
        component: () => import('@/views/course/QaPage.vue'),
        meta: { title: '智能问答', keepAlive: true }
      },
      {
        path: 'study',
        component: () => import('@/views/course/StudyPage.vue'),
        meta: { title: '复习助手' }
      },
      {
        path: 'history',
        component: () => import('@/views/course/HistoryPage.vue'),
        meta: { title: '我的历史' }
      },
      {
        path: 'history/:qaId(\\d+)',
        component: () => import('@/views/course/HistoryDetail.vue'),
        meta: { title: '问答详情' }
      },
      {
        path: 'favorites',
        component: () => import('@/views/course/HistoryPage.vue'),
        meta: { title: '我的收藏' }
      },
      {
        path: 'admin/documents',
        component: documents,
        meta: { title: '资料管理', adminOnly: true }
      },
      {
        path: 'admin/categories',
        component: () => import('@/views/course/CategoriesPage.vue'),
        meta: { title: '分类管理', adminOnly: true }
      },
      {
        path: 'admin/users',
        component: () => import('@/views/course/UsersPage.vue'),
        meta: { title: '用户管理', adminOnly: true }
      },
      {
        path: 'admin/feedback',
        component: () => import('@/views/course/FeedbackPage.vue'),
        meta: { title: '用户反馈', adminOnly: true }
      },
      {
        path: 'admin/stats',
        component: () => import('@/views/course/StatsPage.vue'),
        meta: { title: '统计概览', adminOnly: true }
      },
      {
        path: '403',
        component: () => import('@/views/course/StatusPage.vue'),
        meta: { title: '无访问权限', status: 403 }
      },
      {
        path: ':pathMatch(.*)*',
        component: () => import('@/views/course/StatusPage.vue'),
        meta: { title: '页面不存在', status: 404 }
      }
    ]
  }
]
