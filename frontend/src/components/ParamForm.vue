<!--
  ParamForm — dynamic form for ScriptParam[].
  - Coerces number/boolean to the right runtime type, mirrors back as plain string map.
  - Supports placeholder + helpText (added in the v2 ScriptParam schema).
  - V2: conditional visibility — each param may carry visibleWhenJson. Hidden params
        are not rendered AND not emitted in update:modelValue. The form re-evaluates
        visibility on every value change, so picking a parent select immediately
        shows/hides dependents.
  - `file` params are NOT rendered here: the caller (ExecuteView) owns their
        upload widget. They used to fall through to a plain text input and
        duplicate the upload row.
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
      :rules="rulesByName.get(p.name)"
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
          v-for="opt in optionsOf(p)"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
      <!-- boolean -->
      <el-switch
        v-else-if="p.type === 'boolean'"
        v-model="form[p.name]"
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
import { parseOptions, visibilityMap } from '../utils/params'
import { PARAM_TYPE } from '../utils/labels'

const props = defineProps({
  params: { type: Array, required: true },
  modelValue: { type: Object, required: true },
  // Optional overrides applied during seed (e.g. last-used params from localStorage).
  initialValues: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['update:modelValue'])

const formRef = ref(null)
const form = reactive({})

const orderedParams = computed(() =>
  [...(props.params || [])].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
)

// Params whose widget belongs to the caller (upload section).
const formParams = computed(() => orderedParams.value.filter((p) => p.type !== PARAM_TYPE.FILE))

/**
 * Structural fingerprint of the param list. The form is reseeded only when the
 * shape actually changes — a deep watcher on `params` used to rebuild (and so
 * clobber whatever the user had typed) on any nested mutation.
 */
const paramsSignature = computed(() => formParams.value.map((p) => [
  p.name, p.type, p.required ? 1 : 0, p.defaultValue ?? '', p.options ?? '',
  p.sortOrder ?? 0, p.visibleWhenJson ?? ''
].join(':')).join('|'))

function seedValue(p) {
  const last = props.initialValues?.[p.name]
  if (last != null && last !== '') return last
  if (p.defaultValue != null && p.defaultValue !== '') return p.defaultValue
  return p.type === PARAM_TYPE.BOOLEAN ? false : ''
}

function coerce(p, v) {
  if (p.type === PARAM_TYPE.NUMBER) {
    if (v === '' || v == null) return undefined
    const n = Number(v)
    return isNaN(n) ? undefined : n
  }
  if (p.type === PARAM_TYPE.BOOLEAN) {
    if (typeof v === 'boolean') return v
    return v === true || v === 'true' || v === '1'
  }
  return v
}

function rebuild() {
  for (const k of Object.keys(form)) delete form[k]
  for (const p of formParams.value) {
    if (!p.name) continue
    form[p.name] = coerce(p, seedValue(p))
  }
}

// Reseed on a real change of the param set or of the seed overrides. Both
// watchers compare content signatures rather than object identity: callers
// routinely pass a freshly-built object/array on every render, and an identity
// watcher would rebuild the form (wiping what the user typed) each time the
// parent re-rendered. Deep watchers on the props had the same effect.
const initialSignature = computed(() => JSON.stringify(props.initialValues || {}))

watch([paramsSignature, initialSignature], rebuild, { immediate: true })

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

// V2: conditional visibility, evaluated once per value change and reused by the
// template, the emitter and resetToDefaults().
const visibility = computed(() => visibilityMap(formParams.value, form, null))

const visibleParams = computed(() =>
  formParams.value.filter((p) => visibility.value.get(p.name)?.visible !== false)
)

const hiddenNameSet = computed(() => {
  const s = new Set()
  for (const [n, info] of visibility.value.entries()) {
    if (info.visible === false) s.add(n)
  }
  return s
})

// Rules are derived from the param declaration, not from user input — building
// them once per param instead of per render keeps the v-for cheap.
const rulesByName = computed(() => {
  const m = new Map()
  for (const p of formParams.value) m.set(p.name, rulesFor(p))
  return m
})

// Helpers for the caller's drawer.
defineExpose({
  validate: () => (formRef.value ? formRef.value.validate() : Promise.resolve()),
  resetToDefaults: () => {
    for (const p of formParams.value) {
      if (!p.name) continue
      if (p.defaultValue != null && p.defaultValue !== '') {
        form[p.name] = coerce(p, p.defaultValue)
      } else if (p.type === PARAM_TYPE.BOOLEAN) {
        form[p.name] = false
      } else {
        form[p.name] = ''
      }
    }
  }
})

function paramLabel(p) {
  let s = p.label || p.name
  if (p.required) s += ' *'
  return s
}

function optionsOf(p) {
  return parseOptions(p.options)
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
  if (p.type === PARAM_TYPE.NUMBER) {
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
  if (p.type === PARAM_TYPE.SELECT) {
    const opts = optionsOf(p)
    r.push({
      validator: (_, value, cb) => {
        if (!p.required && (value === '' || value == null)) return cb()
        if (opts.some((o) => o.value === String(value))) return cb()
        cb(new Error(`必须是 ${opts.map((o) => o.label).join('/')} 之一`))
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
