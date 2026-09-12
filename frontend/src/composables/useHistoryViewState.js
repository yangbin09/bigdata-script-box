// composables/useHistoryViewState.js — preserves HistoryView filter / page /
// scroll position across the "open detail drawer → close → return" cycle.
//
// Why this exists:
//   When a user clicks a history row to inspect details, scrolls the page,
//   then closes the drawer, they expect to land back where they were.
//   With virtual scrolling + filter changes (which re-fetch the list), the
//   page resets to row 1 and the scroll jumps to top.
//
// Approach:
//   Snapshot { status, scriptId, tenantId, keyword, dateFrom, dateTo, page,
//   pageSize, scrollTop } when leaving the table (closing drawer or navigating
//   away). Restore on next mount via onMounted.

import { ref, onMounted, onBeforeUnmount } from 'vue'
import { getItem, setItem } from '../utils/storage'

const KEY = 'sb.historyViewState.v1'

export function useHistoryViewState() {
  const restored = ref(false)

  function snapshot(state) {
    if (!state) return
    try {
      setItem(KEY, {
        status: state.status,
        scriptId: state.scriptId,
        tenantId: state.tenantId,
        keyword: state.keyword,
        dateFrom: state.dateFrom,
        dateTo: state.dateTo,
        page: state.page,
        pageSize: state.pageSize,
        scrollTop: state.scrollTop
      })
    } catch { /* quota / disabled storage */ }
  }

  function restore() {
    const s = getItem(KEY, null)
    if (!s) {
      restored.value = true
      return null
    }
    restored.value = true
    return s
  }

  function clear() {
    try { setItem(KEY, null) } catch { /* ignore */ }
  }

  // Track scrollTop with a passive listener. Caller passes the table ref.
  function trackScroll(tableRef) {
    if (!tableRef || !tableRef.value) return () => {}
    const el = tableRef.value?.$el?.querySelector?.('.el-scrollbar__wrap')
      || tableRef.value?.bodyWrapper
      || tableRef.value?.$el
    if (!el) return () => {}
    let raf = 0
    const onScroll = () => {
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(() => {
        const top = el.scrollTop || el.scrollLeft || 0
        scrollTopRef.value = top
      })
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      el.removeEventListener('scroll', onScroll)
      cancelAnimationFrame(raf)
    }
  }

  const scrollTopRef = ref(0)

  function applyScrollTop(tableRef, top) {
    if (!top || !tableRef || !tableRef.value) return
    const el = tableRef.value?.$el?.querySelector?.('.el-scrollbar__wrap')
      || tableRef.value?.bodyWrapper
      || tableRef.value?.$el
    if (!el) return
    requestAnimationFrame(() => {
      el.scrollTop = top
      el.scrollLeft = el.scrollLeft || 0
    })
  }

  return { snapshot, restore, clear, trackScroll, applyScrollTop, scrollTop: scrollTopRef, restored }
}
