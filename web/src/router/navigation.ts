export const courseMenus = [
  { path: '/documents', label: '资料库', icon: 'Collection' },
  { path: '/qa', label: '智能问答', icon: 'QuestionFilled' },
  { path: '/study', label: '复习助手', icon: 'Document' },
  { path: '/history', label: '我的历史', icon: 'Clock' },
  { path: '/favorites', label: '我的收藏', icon: 'Star' },
  { path: '/admin/documents', label: '资料管理', icon: 'Document', adminOnly: true },
  { path: '/admin/categories', label: '分类管理', icon: 'Folder', adminOnly: true },
  { path: '/admin/users', label: '用户管理', icon: 'User', adminOnly: true },
  { path: '/admin/feedback', label: '用户反馈', icon: 'ChatDotRound', adminOnly: true },
  { path: '/admin/stats', label: '统计概览', icon: 'DataAnalysis', adminOnly: true }
] as const
export function menusForRole(isAdmin: boolean) {
  return courseMenus.filter(item => !('adminOnly' in item) || isAdmin)
}
