/**
 * Format a LocalDateTime array [yyyy, MM, dd, HH, mm, ss, ...] returned by Jackson
 * (Spring serializes java.time.LocalDateTime as a JSON array, not ISO string,
 * unless jackson-datatype-jsr310 is registered. To be defensive, handle both).
 */
export function formatDateTime(v) {
  if (!v) return '-'
  if (typeof v === 'string') {
    // Could be "2024-01-01T12:34:56" or "2024-01-01 12:34:56"
    return v.replace('T', ' ').replace(/\..*$/, '')
  }
  if (Array.isArray(v)) {
    const [y, mo, d, h = 0, mi = 0, s = 0] = v
    const pad = (n) => String(n).padStart(2, '0')
    return `${y}-${pad(mo)}-${pad(d)} ${pad(h)}:${pad(mi)}:${pad(s)}`
  }
  return String(v)
}

export function formatDuration(ms) {
  if (ms == null) return '-'
  if (ms < 1000) return `${ms} ms`
  const s = ms / 1000
  if (s < 60) return `${s.toFixed(2)} s`
  const m = Math.floor(s / 60)
  const rs = (s % 60).toFixed(1)
  return `${m}m ${rs}s`
}

export function parseParamsJson(json) {
  if (!json) return {}
  try { return JSON.parse(json) } catch { return { _raw: json } }
}