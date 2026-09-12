/**
 * Thin wrapper over localStorage/sessionStorage with JSON (de)serialization.
 * Used for last-tenant, last-params and other small UX-persistence keys.
 * Silently no-ops if the storage is unavailable (e.g. private mode / quota).
 */

/** Every persistence key used by the app, so they don't drift as string literals. */
export const KEYS = {
  LAST_TENANT: 'sb.lastTenantId',
  LAST_PARAMS: 'sb.lastParams',
  RERUN: 'sb.rerun',
  RERUN_RESULT: 'sb.rerunResult',
  SETTINGS_TAB: 'sb.settingsTab',
  // V3 (PR-0): 表单草稿 / 文件失效 / 任务中心 / 快捷操作
  DRAFT_PREFIX: 'sb.draft.',          // sb.draft.<scriptId>.<tenantId> -> { values, savedAt, dirty }
  FAILED_UPLOADS: 'sb.failedUploads', // 失效文件 token 列表
  TASK_CENTER_OPEN: 'sb.taskCenterOpen',
  QUICK_ACTION_ORDER: 'sb.quickActionOrder',
  RECENT_ACCESS: 'sb.recentAccess'    // 全局查找的"最近访问"
}

/**
 * V3 (PR-0): 草稿 key 由 scriptId+tenantId 拼接 —— 严格按维度隔离，
 * 满足"切换租户不套另一租户草稿"的验收。
 */
export function draftKey(scriptId, tenantId) {
  return `${KEYS.DRAFT_PREFIX}${scriptId}.${tenantId || 'none'}`
}

function probe(store) {
  try {
    const t = '__sb_probe__'
    store.setItem(t, '1')
    store.removeItem(t)
    return store
  } catch (_) {
    return null
  }
}

const safeLocal = typeof window === 'undefined' ? null : probe(window.localStorage)
const safeSession = typeof window === 'undefined' ? null : probe(window.sessionStorage)

function make(store) {
  return {
    get(key, fallback = null) {
      if (!store) return fallback
      const raw = store.getItem(key)
      if (raw == null) return fallback
      try { return JSON.parse(raw) } catch { return fallback }
    },
    set(key, value) {
      if (!store) return
      try { store.setItem(key, JSON.stringify(value)) } catch (_) { /* quota */ }
    },
    remove(key) {
      if (!store) return
      try { store.removeItem(key) } catch (_) { /* ignore */ }
    }
  }
}

const local = make(safeLocal)
const session = make(safeSession)

export const getItem = local.get
export const setItem = local.set
export const removeItem = local.remove

export const getSessionItem = session.get
export const setSessionItem = session.set
export const removeSessionItem = session.remove

/** Read-and-delete a one-shot handoff payload (used by the rerun flows). */
export function takeSessionItem(key, fallback = null) {
  const v = session.get(key, fallback)
  session.remove(key)
  return v
}
