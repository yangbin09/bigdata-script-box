/**
 * Thin wrapper over localStorage with JSON (de)serialization.
 * Used for last-tenant, last-params and other small UX-persistence keys.
 * Silently no-ops if localStorage is unavailable (e.g. private mode).
 */
const safeStorage = (() => {
  try {
    const t = '__sb_probe__'
    window.localStorage.setItem(t, '1')
    window.localStorage.removeItem(t)
    return window.localStorage
  } catch (_) {
    return null
  }
})()

export function getItem(key, fallback = null) {
  if (!safeStorage) return fallback
  const raw = safeStorage.getItem(key)
  if (raw == null) return fallback
  try { return JSON.parse(raw) } catch { return fallback }
}

export function setItem(key, value) {
  if (!safeStorage) return
  try { safeStorage.setItem(key, JSON.stringify(value)) } catch { /* quota */ }
}

export function removeItem(key) {
  if (!safeStorage) return
  try { safeStorage.removeItem(key) } catch { /* */ }
}