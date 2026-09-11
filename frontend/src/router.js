import { createRouter, createWebHashHistory } from 'vue-router'

// Hash history works behind any prefix and avoids Spring fallback complexity.
const routes = [
  { path: '/',             name: 'execute',  component: () => import('./views/ExecuteView.vue') },
  { path: '/scripts',      name: 'scripts',  component: () => import('./views/ScriptsView.vue') },
  { path: '/scripts/edit', name: 'script-edit', component: () => import('./views/ScriptEditView.vue') },
  { path: '/tenants',      name: 'tenants',  component: () => import('./views/TenantsView.vue') },
  { path: '/history',      name: 'history',  component: () => import('./views/HistoryView.vue') }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})