/**
 * Canonical status vocabulary for the UI.
 * Backend stores success/timeout as booleans; we map to a small enum so labels stay consistent
 * across pages (Execute / History / Detail drawer / result panel).
 */

export const STATUS = {
  SUCCESS: 'success',
  FAILED: 'failed',
  TIMEOUT: 'timeout',
  RUNNING: 'running',
  CANCELLED: 'cancelled',
  DISABLED: 'disabled',
  UNKNOWN: 'unknown'
}

export const STATUS_LABEL = {
  [STATUS.SUCCESS]: '成功',
  [STATUS.FAILED]: '失败',
  [STATUS.TIMEOUT]: '超时',
  [STATUS.RUNNING]: '执行中',
  [STATUS.CANCELLED]: '已取消',
  [STATUS.DISABLED]: '已禁用',
  [STATUS.UNKNOWN]: '—'
}

export const STATUS_TAG_TYPE = {
  [STATUS.SUCCESS]: 'success',
  [STATUS.FAILED]: 'danger',
  [STATUS.TIMEOUT]: 'warning',
  [STATUS.RUNNING]: 'primary',
  [STATUS.CANCELLED]: 'info',
  [STATUS.DISABLED]: 'info',
  [STATUS.UNKNOWN]: 'info'
}

/** Map a history row to its UI status enum. */
export function statusOfHistory(h) {
  if (!h) return STATUS.UNKNOWN
  // status string from backend takes precedence when present
  if (h.status === 'CANCELLED') return STATUS.CANCELLED
  if (h.status === 'RUNNING') return STATUS.RUNNING
  if (h.timeout) return STATUS.TIMEOUT
  if (h.success) return STATUS.SUCCESS
  return STATUS.FAILED
}

/** Map a script (enabled) to UI status enum. */
export function statusOfScript(s) {
  if (!s) return STATUS.UNKNOWN
  if (s.enabled === false) return STATUS.DISABLED
  return STATUS.SUCCESS
}

// ---- V2: Risk level (script.riskLevel) ----

export const RISK_LEVEL = {
  READ_ONLY: 'READ_ONLY',
  WRITE: 'WRITE',
  DANGEROUS: 'DANGEROUS'
}

export const RISK_LEVEL_LABEL = {
  [RISK_LEVEL.READ_ONLY]: '只读',
  [RISK_LEVEL.WRITE]: '写操作',
  [RISK_LEVEL.DANGEROUS]: '危险'
}

/** Element Plus el-tag type: info for read-only, warning for write, danger for dangerous. */
export const RISK_LEVEL_TAG_TYPE = {
  [RISK_LEVEL.READ_ONLY]: 'info',
  [RISK_LEVEL.WRITE]: 'warning',
  [RISK_LEVEL.DANGEROUS]: 'danger'
}

export const RISK_LEVEL_OPTIONS = [
  { value: RISK_LEVEL.READ_ONLY, label: '只读 — 不会修改外部状态' },
  { value: RISK_LEVEL.WRITE, label: '写操作 — 会修改文件系统/数据库' },
  { value: RISK_LEVEL.DANGEROUS, label: '危险 — 删除/不可恢复/对外有副作用' }
]

export const RISK_CONFIRM_TOKEN = 'CONFIRM'

/** Returns the canonical level, defaulting to READ_ONLY when missing/blank. */
export function normalizeRiskLevel(raw) {
  if (!raw) return RISK_LEVEL.READ_ONLY
  const upper = String(raw).trim().toUpperCase()
  return [RISK_LEVEL.READ_ONLY, RISK_LEVEL.WRITE, RISK_LEVEL.DANGEROUS].includes(upper)
    ? upper
    : RISK_LEVEL.READ_ONLY
}