<!--
  ParamForm — dynamic form for ScriptParam[].
  - Coerces number/boolean to the right runtime type, mirrors back as plain string map.
  - Supports placeholder + helpText (added in the v2 ScriptParam schema).
  - V2: conditional visibility — each param may carry visibleWhenJson. Hidden params
        are not rendered AND not emitted in update:modelValue. The form re-evaluates
        visibility on every value change, so picking a parent select immediately
        shows/hides dependents.
  - Seed value precedence (highest first):
        1) initialValues (last-params from localStorage)
        2) param defaultValue
        3) sensible blank per type
  - Exposes resetToDefaults() to "restore defaults" button in the drawer.
-->
<template>
  <el-form
    ref="formRef"
    :model="form"
    label-position="top"
    class="sb-param-form"
  >
    <el-form-item
      v-for="p in visibleParams"
      :key="p.name"
      :label="paramLabel(p)"
      :prop="p.name"
      :rules="rulesFor(p)"
    >
      <!-- text -->
      <el-input
        v-if="p.type === 'text'"
        v-model="form[p.name]"
        :placeholder="p.placeholder || p.defaultValue || ''"
        clearable
      />
      <!-- textarea -->
      <el-input
        v-else-if="p.type === 'textarea'"
        v-model="form[p.name]"
        type="textarea"
        :rows="3"
        :placeholder="p.placeholder || p.defaultValue || ''"
      />
      <!-- number -->
      <el-input-number
        v-else-if="p.type === 'number'"
        v-model="form[p.name]"
        :placeholder="p.placeholder || (p.defaultValue ?? '')"
        style="width: 100%"
        controls-position="right"
      />
      <!-- select -->
      <el-select
        v-else-if="p.type === 'select'"
        v-model="form[p.name]"
        :placeholder="p.placeholder || (p.defaultValue ? `默认: ${p.defaultValue}` : '请选择')"
        style="width: 100%"
      >
        <el-option
          v-for="opt in selectOptions(p)"
          :key="opt"
          :label="opt"
          :value="opt"
        />
      </el-select>
      <!-- boolean -->
      <el-switch
        v-else-if="p.type === 'boolean'"
        v-model="form[p.name]"
        active-text="true"
        inactive-text="false"
      />
      <!-- date -->
      <el-date-picker
        v-else-if="p.type === 'date'"
        v-model="form[p.name]"
        type="date"
        value-format="YYYY-MM-DD"
        style="width: 100%"
      />
      <!-- fallback -->
      <el-input v-else v-model="form[p.name]" />
      <div v-if="p.helpText" class="sb-param-help">{{ p.helpText }}</div>
    </el-form-item>

    <div v-if="!visibleParams.length" class="sb-no-params">
      <el-icon><InfoFilled /></el-icon>
      此脚本当前没有可见参数，点击「执行」直接运行。
    </div>
  </el-form>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'

const props = defineProps({
  params: { type: Array, required: true },
  modelValue: { type: Object, required: true },
  // Optional overrides applied during seed (e.g. last-used params from localStorage).
  initialValues: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['update:modelValue'])

const formRef = ref(null)
const form = reactive({})

function seedValue(p) {
  const last = props.initialValues?.[p.name]
  if (last != null && last !== '') return last
  if (p.defaultValue != null && p.defaultValue !== '') return p.defaultValue
  return p.type === 'boolean' ? false : ''
}

function coerce(p, v) {
  if (p.type === 'number') {
    if (v === '' || v == null) return undefined
    const n = Number(v)
    return isNaN(n) ? undefined : n
  }
  if (p.type === 'boolean') {
    if (typeof v === 'boolean') return v
    return v === true || v === 'true' || v === '1'
  }
  return v
}

function rebuild() {
  for (const k of Object.keys(form)) delete form[k]
  for (const p of props.params || []) {
    if (!p.name) continue
    form[p.name] = coerce(p, seedValue(p))
  }
}
rebuild()
watch(() => [props.params, props.initialValues], rebuild, { deep: true })

// Mirror local form back to parent as plain string map. Hidden params are
// excluded: we never publish a value the user couldn't see.
watch(form, (v) => {
  const out = {}
  const hidden = hiddenNameSet.value
  for (const [k, val] of Object.entries(v)) {
    if (hidden.has(k)) continue
    if (val === undefined || val === null) { out[k] = ''; continue }
    out[k] = String(val)
  }
  emit('update:modelValue', out)
}, { deep: true })

const orderedParams = computed(() =>
  [...(props.params || [])].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
)

// V2: parse visibleWhenJson for each declared param, then filter using current form.
const parsedVisibility = computed(() => {
  const m = new Map()  // name -> { visible: boolean, invalid: boolean, reason?: string }
  for (const p of orderedParams.value) {
    if (!p.name) continue
    const raw = (p.visibleWhenJson || '').trim()
    if (!raw) { m.set(p.name, { visible: true, invalid: false }); continue }
    let rule
    try { rule = JSON.parse(raw) } catch { m.set(p.name, { visible: true, invalid: true, reason: 'visibleWhenJson 不是合法 JSON' }); continue }
    if (!rule || typeof rule !== 'object') { m.set(p.name, { visible: true, invalid: true, reason: 'visibleWhenJson 不是对象' }); continue }
    const ref = (rule.param || '').trim()
    const op = String(rule.operator || '').trim()
    const val = rule.value == null ? '' : String(rule.value)
    if (!ref) { m.set(p.name, { visible: true, invalid: true, reason: 'visibleWhenJson 缺少 param 字段' }); continue }
    if (op !== 'equals' && op !== 'notEquals') { m.set(p.name, { visible: true, invalid: true, reason: `operator 不支持: ${op || '(空)'}` }); continue }
    const current = form[ref]
    const cur = current == null ? '' : String(current)
    const eq = cur === val
    const visible = op === 'equals' ? eq : !eq
    m.set(p.name, { visible, invalid: false, ref })
  }
  return m
})

const visibleParams = computed(() =>
  orderedParams.value.filter((p) => {
    const info = parsedVisibility.value.get(p.name)
    return !info || info.visible !== false
  })
)

const hiddenNameSet = computed(() => {
  const s = new Set()
  for (const [n, info] of parsedVisibility.value.entries()) {
    if (info.visible === false) s.add(n)
  }
  return s
})

// Helpers for the drawer: which params are hidden right now (used by the
// "复位默认值" button and for debug chips in the execute drawer).
defineExpose({
  validate: () => (formRef.value ? formRef.value.validate() : Promise.resolve()),
  resetToDefaults: () => {
    for (const p of props.params || []) {
      if (!p.name) continue
      if (p.defaultValue != null && p.defaultValue !== '') {
        form[p.name] = coerce(p, p.defaultValue)
      } else if (p.type === 'boolean') {
        form[p.name] = false
      } else {
        form[p.name] = ''
      }
    }
  },
  hiddenParams: () => {
    const out = []
    for (const [n, info] of parsedVisibility.value.entries()) {
      if (info.visible === false) out.push(n)
    }
    return out
  }
})

function paramLabel(p) {
  let s = p.label || p.name
  if (p.required) s += ' *'
  return s
}

function selectOptions(p) {
  return (p.options || '').split(',').map((s) => s.trim()).filter(Boolean)
}

function rulesFor(p) {
  const r = []
  if (p.required) {
    r.push({
      required: true,
      message: `${p.label || p.name} 不能为空`,
      trigger: ['blur', 'change']
    })
  }
  if (p.type === 'number') {
    r.push({
      validator: (_, value, cb) => {
        if (value === '' || value == null) return cb()
        if (typeof value === 'number' && !isNaN(value)) return cb()
        if (typeof value === 'string' && !isNaN(Number(value))) return cb()
        cb(new Error('必须是数字'))
      },
      trigger: ['blur', 'change']
    })
  }
  if (p.type === 'select') {
    r.push({
      validator: (_, value, cb) => {
        if (!p.required && (value === '' || value == null)) return cb()
        const opts = selectOptions(p)
        if (opts.includes(String(value))) return cb()
        cb(new Error(`必须是 ${opts.join('/')} 之一`))
      },
      trigger: ['blur', 'change']
    })
  }
  return r
}
</script>

<style scoped>
.sb-param-form :deep(.el-form-item) { margin-bottom: 14px; }
.sb-param-form :deep(.el-form-item__label) { font-weight: 500; padding-bottom: 4px; }

.sb-no-params {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--sb-text-3);
  font-size: 13px;
  padding: 8px 0;
}
</style>
