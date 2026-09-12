/**
 * ScriptParam helpers shared by the editor (ScriptEditView) and the runtime
 * form (ParamForm).
 *
 * Both files used to carry their own copy of the option parser, and two
 * divergent copies of the `visibleWhenJson` validator — the editor rejected
 * rules that referenced an undeclared parameter while the runtime silently
 * accepted them. One implementation, one vocabulary.
 */

/**
 * Parse `ScriptParam.options` (comma-separated) into [{label, value}].
 * Each entry may be "label:value" or just "value" (then label === value).
 * The legacy "ls,ps,df" form keeps working unchanged.
 */
export function parseOptions(raw) {
  return String(raw || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((entry) => {
      const i = entry.indexOf(':')
      if (i >= 0) {
        return { label: entry.substring(0, i).trim(), value: entry.substring(i + 1).trim() }
      }
      return { label: entry, value: entry }
    })
}

/** Serialize [{label, value}] back into the comma-separated `options` string. */
export function serializeOptions(opts) {
  return (opts || [])
    .map((o) => {
      const label = (o.label || '').trim()
      const value = (o.value || '').trim()
      if (!label && !value) return null
      // If label === value, keep the old "value-only" form so legacy scripts
      // round-trip unchanged. Otherwise emit "label:value".
      if (!label || label === value) return value || label
      return `${label}:${value}`
    })
    .filter(Boolean)
    .join(',')
}

export const VISIBILITY_OPERATORS = ['equals', 'notEquals']

/**
 * Validate a `visibleWhenJson` rule.
 *
 * @param {string} raw             the raw JSON text
 * @param {Set<string>} declaredNames  when provided, a rule referencing an
 *        undeclared parameter is an error (editor behaviour). The runtime
 *        omits it, so a stale rule degrades to "always visible" instead of
 *        breaking execution.
 * @returns {{error: string} | {rule: null} | {rule: {param, operator, value}}}
 */
export function parseVisibilityRule(raw, declaredNames) {
  const text = String(raw || '').trim()
  if (!text) return { rule: null, error: null }
  let parsed
  try { parsed = JSON.parse(text) } catch { return { rule: null, error: 'visibleWhenJson 不是合法 JSON' } }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { rule: null, error: 'visibleWhenJson 必须是 JSON 对象' }
  }
  const param = typeof parsed.param === 'string' ? parsed.param.trim() : ''
  const operator = String(parsed.operator || '').trim()
  if (!param) return { rule: null, error: '缺少 param 字段（要引用的参数名）' }
  if (!VISIBILITY_OPERATORS.includes(operator)) {
    return { rule: null, error: `operator 必须是 equals 或 notEquals（当前: ${operator || '(空)'}）` }
  }
  if (declaredNames && !declaredNames.has(param)) {
    return { rule: null, error: `引用了未声明的参数 "${param}"` }
  }
  return {
    rule: { param, operator, value: parsed.value == null ? '' : String(parsed.value) },
    error: null
  }
}

/** Does `rule` hold for the current value map? Unknown params compare as ''. */
export function evaluateRule(rule, values) {
  if (!rule) return true
  const current = values?.[rule.param]
  const eq = (current == null ? '' : String(current)) === rule.value
  return rule.operator === 'equals' ? eq : !eq
}

/**
 * Resolve visibility for every declared param against the current values.
 *
 * @returns {Map<string, {visible: boolean, invalid: boolean, reason?: string, ref?: string}>}
 * Params without a rule (or with an invalid one) stay visible — a broken rule
 * hides nothing from the operator.
 */
export function visibilityMap(params, values, declaredNames) {
  const out = new Map()
  for (const p of params || []) {
    if (!p?.name) continue
    const { rule, error } = parseVisibilityRule(p.visibleWhenJson, declaredNames)
    if (error) {
      out.set(p.name, { visible: true, invalid: true, reason: error })
      continue
    }
    if (!rule) {
      out.set(p.name, { visible: true, invalid: false })
      continue
    }
    out.set(p.name, {
      visible: evaluateRule(rule, values),
      invalid: false,
      ref: rule.param
    })
  }
  return out
}
