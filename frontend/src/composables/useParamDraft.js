import { ref, watch } from 'vue'
import { getItem, setItem, removeItem, draftKey, KEYS } from '../utils/storage'

/**
 * V3 (PR-3): 参数草稿持久化 + 4 个来源徽章选择。
 *
 * <p>4 个来源：
 * <ul>
 *   <li><b>默认参数</b> — 来自 ScriptParam.defaultValue；</li>
 *   <li><b>上次执行</b> — 来自 sb.lastParams[scriptId]，跨 session 保留；</li>
 *   <li><b>指定方案</b> — 来自 ScriptPreset.values（用户在 Preset 选择器里指定）；</li>
 *   <li><b>未提交草稿</b> — 来自 sb.draft.${scriptId}.${tenantId}，用户在本 session
 *       改了一半还没提交的本地副本。</li>
 * </ul>
 *
 * <p>草稿写入：监听 formValues 变化，debounce 800ms 写 localStorage。
 * <ul>
 *   <li>跳过 {@code type==='file'} 的字段（路径是一次性的，不应复用）；</li>
 *   <li>跳过 {@code sensitive===true} 的字段（明文密码 / token 不入 localStorage）。</li>
 * </ul>
 *
 * <p>切换来源：调用 {@link useParamDraft#applySource}，传入目标来源标识符。
 * 当前来源会被记录到 localStorage（{@code sb.draft.src.${key}}），下次打开抽屉时
 * 自动恢复。
 *
 * <p>撤销：{@link useParamDraft#undo} 在 5 秒窗口内可恢复上一次来源切换前的值。
 */
export const DRAFT_SRC = {
  DEFAULT: 'default',
  LAST: 'last',
  PRESET: 'preset',
  DRAFT: 'draft'
}

const DEBOUNCE_MS = 800
const UNDO_WINDOW_MS = 5000

/**
 * @param {object} opts
 * @param {() => number|string|null} opts.getScriptId
 * @param {() => number|string|null} opts.getTenantId
 * @param {() => any[]} opts.getParams        ScriptParam[] 当前表单定义
 * @param {() => any[]} opts.getPresets       ScriptPreset[] 可选
 * @param {() => string|null} opts.getPresetId 当前选中的 presetId（可为 null）
 * @param {object} opts.formValues            ref<Record<string, any>> 表单值容器
 * @param {(vals: Record<string, any>) => void} opts.applyValues 把来源值灌进表单
 */
export function useParamDraft(opts) {
  const sourceKey = `${KEYS.DRAFT_PREFIX}src.${opts.getScriptId() || 0}.${opts.getTenantId() || 'none'}`
  const currentSource = ref(getItem(sourceKey, DRAFT_SRC.DEFAULT))

  // 撤销栈：最多 1 条（仅保留"上一次来源"的值，便于 5s 撤销）
  let lastSnapshot = null
  let lastSnapshotAt = 0

  function snapshotForm() {
    return JSON.parse(JSON.stringify(opts.formValues.value || {}))
  }

  function pushUndo() {
    lastSnapshot = snapshotForm()
    lastSnapshotAt = Date.now()
  }

  function readDraft() {
    const k = draftKey(opts.getScriptId(), opts.getTenantId())
    return getItem(k, null)
  }

  function readLast() {
    const all = getItem(KEYS.LAST_PARAMS, {}) || {}
    return all[opts.getScriptId()] || null
  }

  function readPreset() {
    const pid = opts.getPresetId()
    if (!pid) return null
    const presets = opts.getPresets() || []
    const p = presets.find((x) => String(x.id) === String(pid))
    return p ? (p.values || null) : null
  }

  function readDefaults() {
    const out = {}
    for (const p of opts.getParams() || []) {
      if (p.defaultValue != null) out[p.name] = p.defaultValue
    }
    return out
  }

  /**
   * 把来源值"灌进"表单。file / sensitive 字段保留当前值不覆盖：
   * 这两类不适合从 default / last / preset 套（路径是临时的，敏感字段不写本地）。
   */
  function applySource(source) {
    pushUndo()
    let values = null
    if (source === DRAFT_SRC.DRAFT) values = readDraft()
    else if (source === DRAFT_SRC.LAST) values = readLast()
    else if (source === DRAFT_SRC.PRESET) values = readPreset()
    else values = readDefaults()
    if (!values || typeof values !== 'object') values = {}
    // file / sensitive 字段不动
    const preserve = {}
    for (const p of opts.getParams() || []) {
      if (p.type === 'file' || p.sensitive === true) {
        if (opts.formValues.value[p.name] !== undefined) {
          preserve[p.name] = opts.formValues.value[p.name]
        }
      }
    }
    opts.applyValues({ ...values, ...preserve })
    currentSource.value = source
    setItem(sourceKey, source)
  }

  function undo() {
    if (!lastSnapshot) return false
    if (Date.now() - lastSnapshotAt > UNDO_WINDOW_MS) {
      lastSnapshot = null
      return false
    }
    opts.applyValues(lastSnapshot)
    lastSnapshot = null
    return true
  }

  // Debounced draft writer: skip file + sensitive
  let timer = null
  watch(
    () => opts.formValues.value,
    (nv) => {
      if (!opts.getScriptId() || !opts.getTenantId()) return
      clearTimeout(timer)
      timer = setTimeout(() => {
        const filtered = {}
        for (const p of opts.getParams() || []) {
          if (p.type === 'file' || p.sensitive === true) continue
          if (nv[p.name] !== undefined) filtered[p.name] = nv[p.name]
        }
        const k = draftKey(opts.getScriptId(), opts.getTenantId())
        if (Object.keys(filtered).length) {
          setItem(k, { values: filtered, dirty: true, savedAt: Date.now() })
        } else {
          removeItem(k)
        }
      }, DEBOUNCE_MS)
    },
    { deep: true }
  )

  function clearDraft() {
    removeItem(draftKey(opts.getScriptId(), opts.getTenantId()))
  }

  return {
    currentSource,
    applySource,
    undo,
    clearDraft,
    hasDraft: () => readDraft() != null,
    hasLast: () => readLast() != null,
    DRAFT_SRC
  }
}