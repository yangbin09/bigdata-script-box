import { defineStore } from 'pinia'
import { getItem, setItem } from '../utils/storage'

/**
 * V3 (PR-10): "最近访问" 持久化 —— Cmd+K 面板在用户尚未输入查询词时
 * 显示最近跳转过的资源（脚本 / 租户 / 快捷操作）。最多保留 8 条。
 *
 * <p>复用 storage.js 的 getItem/setItem 抽象，让 localStorage 不可用
 * 时静默退化为内存 Map（不会炸 UI）。
 */
const KEY = 'sb.recentItems.v1'
const MAX = 8

export const useRecentStore = defineStore('recent', {
  state: () => ({
    items: []
  }),
  getters: {
    scripts: (state) => state.items.filter((i) => i.kind === 'script'),
    tenants: (state) => state.items.filter((i) => i.kind === 'tenant'),
    quickActions: (state) => state.items.filter((i) => i.kind === 'quick-action')
  },
  actions: {
    bootstrap() {
      const s = getItem(KEY, [])
      this.items = Array.isArray(s) ? s.slice(0, MAX) : []
    },
    touch(item) {
      if (!item || !item.kind || !item.id) return
      const next = [
        { ...item, at: Date.now() },
        ...this.items.filter((i) => !(i.kind === item.kind && String(i.id) === String(item.id)))
      ].slice(0, MAX)
      this.items = next
      try { setItem(KEY, next) } catch { /* quota */ }
    },
    clear() {
      this.items = []
      try { setItem(KEY, []) } catch { /* quota */ }
    }
  }
})
