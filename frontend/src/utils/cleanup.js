/**
 * Cleanup result vocabulary.
 *
 * CleanupPanel and CleanupReportDrawer each mapped the same SUCCESS / PARTIAL /
 * FAILED strings to their own labels, tag types and CSS classes — with slightly
 * different wording. One source of truth here.
 */

export const CLEANUP_RESULT_LABEL = {
  SUCCESS: '成功',
  PARTIAL: '部分失败',
  FAILED: '失败'
}

export function cleanupResultLabel(result) {
  return CLEANUP_RESULT_LABEL[result] || result || '—'
}

export function cleanupResultTagType(result) {
  if (result === 'SUCCESS') return 'success'
  if (result === 'PARTIAL') return 'warning'
  return 'danger'
}

export function cleanupResultClass(result) {
  if (result === 'SUCCESS') return 'sub-success'
  if (result === 'PARTIAL') return 'sub-warning'
  return 'sub-danger'
}

/**
 * Every "we deliberately did not touch this" counter the preview exposes
 * (PreviewStore.Totals). The UI only ever showed skippedRunning, so a preview
 * that skipped symlinks/escapes looked empty.
 */
export function cleanupSkippedTotal(totals) {
  if (!totals) return 0
  return (totals.skippedRunning || 0) + (totals.skippedSymlink || 0) + (totals.skippedEscape || 0)
}
