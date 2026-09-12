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
  SETTINGS_TAB: 'sb.settingsTab'
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
