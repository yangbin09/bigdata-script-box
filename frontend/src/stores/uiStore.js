import { defineStore } from 'pinia'

/**
 * V3 (PR-0): 全局 UI 状态 —— ⌘K 面板、网络横幅、任务中心抽屉。
 *
 * <p>后续 PR（#10 全局查找、#2 任务中心、ExecuteDrawer）会继续扩字段。
 */
export const useUiStore = defineStore('ui', {
  state: () => ({
    /** 全局查找面板是否打开（⌘K / Ctrl+K） */
    globalSearchOpen: false,
    /** 任务中心抽屉是否打开 */
    taskCenterOpen: false,
    /** ⌘K 面板的当前查询词（跨页跳转保留） */
    globalSearchQuery: '',
    /** 跨页跳转前 ⌘K 选中的条目 id，关闭面板时清除 */
    globalSearchPicked: null
  }),
  actions: {
    openGlobalSearch() { this.globalSearchOpen = true },
    closeGlobalSearch() { this.globalSearchOpen = false; this.globalSearchPicked = null },
    openTaskCenter() { this.taskCenterOpen = true },
    closeTaskCenter() { this.taskCenterOpen = false },
    toggleTaskCenter() { this.taskCenterOpen = !this.taskCenterOpen }
  }
})
