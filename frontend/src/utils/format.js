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

// Format a byte size (number) as a human-readable string. Used by artifact
// tables. Rounds to 2 decimals below 10, 1 decimal above, and uses IEC
// binary suffixes (KiB/MiB/GiB) so large file counts stay readable.
export function formatBytes(n) {
  if (n == null || Number.isNaN(n)) return '-'
  if (n < 1024) return `${n} B`
  const units = ['KiB', 'MiB', 'GiB', 'TiB']
  let v = n / 1024
  let i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(v < 10 ? 2 : 1)} ${units[i]}`
}

// Format an ISO timestamp string or epoch ms into a sortable "yyyy-MM-dd HH:mm:ss"
// string. Used by cleanup previews where the backend hands back ISO strings
// rather than the LocalDateTime array that formatDateTime() handles.
export function formatTimestamp(s) {
  if (!s) return '—'
  if (typeof s === 'number') return new Date(s).toISOString().replace('T', ' ').substring(0, 19)
  return String(s).replace('T', ' ').substring(0, 19)
}