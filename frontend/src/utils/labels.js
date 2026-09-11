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
  DISABLED: 'disabled',
  UNKNOWN: 'unknown'
}

export const STATUS_LABEL = {
  [STATUS.SUCCESS]: '成功',
  [STATUS.FAILED]: '失败',
  [STATUS.TIMEOUT]: '超时',
  [STATUS.RUNNING]: '执行中',
  [STATUS.DISABLED]: '已禁用',
  [STATUS.UNKNOWN]: '—'
}

export const STATUS_TAG_TYPE = {
  [STATUS.SUCCESS]: 'success',
  [STATUS.FAILED]: 'danger',
  [STATUS.TIMEOUT]: 'warning',
  [STATUS.RUNNING]: 'primary',
  [STATUS.DISABLED]: 'info',
  [STATUS.UNKNOWN]: 'info'
}

/** Map a history row to its UI status enum. */
export function statusOfHistory(h) {
  if (!h) return STATUS.UNKNOWN
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