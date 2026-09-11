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

// =====================================================================
// Unified Chinese label mappings — keep the backend enum (text / number /
// select / boolean / date / textarea / file) as the canonical storage value;
// only render Chinese labels in the UI.
// =====================================================================

/** Internal ScriptParam.type values (kept English on purpose — matches backend). */
export const PARAM_TYPE = {
  TEXT: 'text',
  NUMBER: 'number',
  SELECT: 'select',
  BOOLEAN: 'boolean',
  DATE: 'date',
  TEXTAREA: 'textarea',
  FILE: 'file'
}

/** Chinese display labels for each ScriptParam.type. */
export const PARAM_TYPE_LABEL = {
  [PARAM_TYPE.TEXT]: '文本',
  [PARAM_TYPE.NUMBER]: '数字',
  [PARAM_TYPE.SELECT]: '下拉选择',
  [PARAM_TYPE.BOOLEAN]: '开关',
  [PARAM_TYPE.DATE]: '日期',
  [PARAM_TYPE.TEXTAREA]: '多行文本',
  [PARAM_TYPE.FILE]: '文件上传'
}

/** Order used by the type dropdown. */
export const PARAM_TYPE_OPTIONS = [
  { value: PARAM_TYPE.TEXT, label: PARAM_TYPE_LABEL[PARAM_TYPE.TEXT] },
  { value: PARAM_TYPE.NUMBER, label: PARAM_TYPE_LABEL[PARAM_TYPE.NUMBER] },
  { value: PARAM_TYPE.SELECT, label: PARAM_TYPE_LABEL[PARAM_TYPE.SELECT] },
  { value: PARAM_TYPE.BOOLEAN, label: PARAM_TYPE_LABEL[PARAM_TYPE.BOOLEAN] },
  { value: PARAM_TYPE.DATE, label: PARAM_TYPE_LABEL[PARAM_TYPE.DATE] },
  { value: PARAM_TYPE.TEXTAREA, label: PARAM_TYPE_LABEL[PARAM_TYPE.TEXTAREA] },
  { value: PARAM_TYPE.FILE, label: PARAM_TYPE_LABEL[PARAM_TYPE.FILE] }
]

/** Per-type placeholder shown when the script author hasn't supplied one. */
export const PARAM_TYPE_PLACEHOLDER = {
  [PARAM_TYPE.TEXT]: '请输入文本',
  [PARAM_TYPE.NUMBER]: '请输入数字',
  [PARAM_TYPE.SELECT]: '请选择',
  [PARAM_TYPE.BOOLEAN]: '',
  [PARAM_TYPE.DATE]: '选择日期',
  [PARAM_TYPE.TEXTAREA]: '可填写多行内容',
  [PARAM_TYPE.FILE]: '点击上传文件'
}

/** Per-type default-value placeholder (sample). */
export const PARAM_TYPE_DEFAULT_PLACEHOLDER = {
  [PARAM_TYPE.TEXT]: 'cobp_dwd',
  [PARAM_TYPE.NUMBER]: '4',
  [PARAM_TYPE.SELECT]: 'ls',
  [PARAM_TYPE.BOOLEAN]: 'false',
  [PARAM_TYPE.DATE]: '2026-09-12',
  [PARAM_TYPE.TEXTAREA]: '示例：\nSELECT * FROM table LIMIT 10;',
  [PARAM_TYPE.FILE]: ''
}

/** Per-type tooltip shown on the 执行表单. */
export const PARAM_TYPE_HELP = {
  [PARAM_TYPE.TEXT]: '单行文本输入。',
  [PARAM_TYPE.NUMBER]: '只接受数字，非数字值会被校验拒绝。',
  [PARAM_TYPE.SELECT]: '从脚本中预定义的选项中选择一个值。',
  [PARAM_TYPE.BOOLEAN]: '开关：开 / 关。',
  [PARAM_TYPE.DATE]: '日期选择器，格式 YYYY-MM-DD。',
  [PARAM_TYPE.TEXTAREA]: '适合 SQL、JSON、多行配置等较长内容。',
  [PARAM_TYPE.FILE]: '执行时文件会上传到本次 Execution 的受控 input 目录，并将服务端文件路径作为参数传给 Shell。'
}

/** Chinese labels for the script.enabled / tenant.enabled / global-variable.enabled flags. */
export const ENABLED_LABEL = {
  true: '已启用',
  false: '已禁用'
}

/** Chinese labels for the boolean sensitive flag. */
export const SENSITIVE_LABEL = {
  true: '是',
  false: '否'
}

/** Standard status chips shown next to a script row (replaces inline ternaries). */
export const STATUS_CHIP = (row) => row?.enabled === false
  ? { label: ENABLED_LABEL.false, type: 'info' }
  : { label: ENABLED_LABEL.true, type: 'success' }

/** Cleanly formatted "result" text for an Execution row, matching the existing UI vocabulary. */
export const RESULT_LABEL = (row) => row?.success ? '成功' : '失败'