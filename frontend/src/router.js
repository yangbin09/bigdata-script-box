import { createRouter, createWebHashHistory } from 'vue-router'

/**
 * 路由收敛（SIMPLIFICATION.md 的 P3）：7 个平级路由 → 3 个。
 * 租户 / 场景 / 清理不再是顶级目的地，而是设置里的子页。
 *
 * 旧路径全部以 redirect 兼容，避免书签失效：
 *   /scripts   → /                          （脚本列表并入工作台）
 *   /tenants   → /settings?tab=tenants
 *   /scenarios → /settings?tab=scenarios
 *   /cleanup   → /settings?tab=cleanup
 */
const routes = [
  // 1) 工作台：找到脚本 + 原地执行（原「执行中心」与「脚本管理」合并）
  { path: '/',             name: 'workbench',   component: () => import('./views/WorkbenchView.vue') },
  // 2) 历史：按脚本 + 按天，而不是流水列表
  { path: '/history',      name: 'history',     component: () => import('./views/HistoryView.vue') },
  // 3) 设置：租户 / 全局变量 / 清理 / 场景编排
  { path: '/settings',     name: 'settings',    component: () => import('./views/SettingsView.vue') },
  // 下钻页：脚本编辑（天然是重页面，不作为顶级目的地）
  { path: '/scripts/edit', name: 'script-edit', component: () => import('./views/ScriptEditView.vue') },

  // ---- 旧路径兼容 ----
  { path: '/scripts',   redirect: '/' },
  { path: '/tenants',   redirect: { name: 'settings', query: { tab: 'tenants' } } },
  { path: '/scenarios', redirect: { name: 'settings', query: { tab: 'scenarios' } } },
  { path: '/cleanup',   redirect: { name: 'settings', query: { tab: 'cleanup' } } }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
