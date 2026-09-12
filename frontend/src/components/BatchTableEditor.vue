<!--
  BatchTableEditor — structured row-based editor for batch executions.

  V3 (PR-8): replaces the plain JSON-per-line textarea. Features:
   * One row per execution; columns derived from active script params.
   * Paste detection: handles Excel/Sheets TSV clipboard. If headers are
     detected, opens ColumnMappingDialog to let the user pick which TSV
     column maps to which param.
   * Per-row validation (required / number / select options) shown inline
     in a red tooltip-style row; overall summary banner "X/Y 行校验失败".
   * "只执行有效行" toggle — when on, validRows are submitted and invalid
     ones skipped with an ElMessage toast.
   * Caller supplies params (from script.params) + initial rows. The editor
     emits `update:rows` with an array of plain { param: value } objects,
     `update:invalidCount`, and `paste-headers` for column-mapping UX.
-->
<template>
  <div class="sb-batch-editor">
    <!-- 工具栏：粘贴 / 重置 / 只执行有效行 -->
    <div class="sb-batch-toolbar">
      <span class="muted">每行对应一次执行；从 Excel/Sheets 直接粘贴可批量填充。</span>
      <div class="sb-batch-actions">
        <el-checkbox v-model="onlyValid">只执行有效行</el-checkbox>
        <el-button size="small" link @click="addRow">+ 增加一行</el-button>
        <el-button size="small" link @click="resetRows" :disabled="!localRows.length">清空</el-button>
      </div>
    </div>

    <!-- 校验汇总横幅 -->
    <el-alert
      v-if="localRows.length"
      :type="invalidCount === 0 ? 'success' : 'warning'"
      :closable="false"
      show-icon
      class="sb-batch-banner"
    >
      <template #title>
        <span v-if="invalidCount === 0">
          共 {{ localRows.length }} 行，全部校验通过
        </span>
        <span v-else>
          共 {{ localRows.length }} 行，{{ invalidCount }} 行校验失败
          <span v-if="onlyValid" class="muted">（将仅提交有效行）</span>
        </span>
      </template>
    </el-alert>

    <!-- 表格 -->
    <el-table
      :data="pagedRows"
      class="sb-batch-table"
      size="small"
      border
      stripe
      :empty-text="'点击「+ 增加一行」开始，或直接粘贴 Excel 数据'"
      :row-class-name="rowClassName"
    >
      <el-table-column label="#" width="50" type="index" align="right" />
      <el-table-column
        v-for="p in paramColumns"
        :key="p.name"
        :label="p.label || p.name"
        :prop="p.name"
        :width="120"
      >
        <template #default="{ row }">
          <el-input
            v-if="p.type === PARAM_TYPE.NUMBER"
            v-model.number="row[p.name]"
            size="small"
            placeholder="数字"
            :class="{ 'is-invalid': rowErrors[row._i]?.[p.name] }"
          />
          <el-input
            v-else-if="p.type === PARAM_TYPE.SELECT"
            v-model="row[p.name]"
            size="small"
            :class="{ 'is-invalid': rowErrors[row._i]?.[p.name] }"
            placeholder="值"
          />
          <el-input
            v-else
            v-model="row[p.name]"
            size="small"
            :class="{ 'is-invalid': rowErrors[row._i]?.[p.name] }"
            placeholder="值"
          />
        </template>
      </el-table-column>
      <el-table-column label="错误" min-width="180">
        <template #default="{ row }">
          <span v-if="rowErrors[row._i] && Object.keys(rowErrors[row._i]).length" class="sb-row-errors">
            <span
              v-for="(msg, k) in rowErrors[row._i]"
              :key="k"
              class="sb-row-error-tag"
              :title="msg"
            >{{ k }}: {{ msg }}</span>
          </span>
          <span v-else-if="row.__touched" class="sb-row-ok">✓</span>
        </template>
      </el-table-column>
      <el-table-column label="" width="60" align="center">
        <template #default="{ $index }">
          <el-button size="small" link type="danger" @click="removeRow($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 列映射对话框 -->
    <ColumnMappingDialog
      v-model="mappingOpen"
      :headers="pendingHeaders"
      :params="params"
      :initial-mapping="pendingMapping"
      @confirm="onMappingConfirm"
    />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ColumnMappingDialog from './ColumnMappingDialog.vue'
import { PARAM_TYPE } from '../utils/labels'
import { parseTsv, suggestMapping, applyMapping } from '../utils/excelPaste'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  params: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'update:invalidCount'])

const onlyValid = ref(false)
const localRows = ref([])
const rowErrors = ref({})  // { [rowIndex]: { paramName: errorMsg } }
const mappingOpen = ref(false)
const pendingHeaders = ref([])
const pendingRows = ref([])
const pendingMapping = ref([])

const paramColumns = computed(() => props.params.filter((p) => p.type !== PARAM_TYPE.FILE))

const invalidCount = computed(() =>
  Object.values(rowErrors.value).filter((e) => Object.keys(e).length > 0).length
)

const pagedRows = computed(() =>
  localRows.value.map((r, i) => ({ ...r, _i: i, __touched: true }))
)

function emitRows() {
  const clean = localRows.value.map((r) => {
    const out = {}
    for (const p of paramColumns.value) {
      const v = r[p.name]
      if (v !== '' && v != null) out[p.name] = String(v)
    }
    return out
  })
  emit('update:modelValue', clean)
  emit('update:invalidCount', invalidCount.value)
}

function addRow() {
  localRows.value.push({})
  emitRows()
}

function removeRow(i) {
  localRows.value.splice(i, 1)
  validateAll()
  emitRows()
}

function resetRows() {
  localRows.value = []
  rowErrors.value = {}
  emitRows()
}

function rowClassName({ row }) {
  if (rowErrors.value[row._i] && Object.keys(rowErrors.value[row._i]).length) {
    return 'sb-row-invalid'
  }
  return ''
}

function validateRow(row, i) {
  const errs = {}
  for (const p of paramColumns.value) {
    const v = row[p.name]
    if (p.required && (v === '' || v == null)) {
      errs[p.name] = '必填'
      continue
    }
    if (v === '' || v == null) continue
    if (p.type === PARAM_TYPE.NUMBER) {
      const n = Number(v)
      if (Number.isNaN(n)) errs[p.name] = '必须是数字'
    } else if (p.type === PARAM_TYPE.SELECT) {
      const opts = (p.options || '').split(/[,\n]/).map((s) => s.trim()).filter(Boolean)
      if (opts.length && !opts.includes(String(v))) {
        errs[p.name] = `必须是 ${opts.join('/')} 之一`
      }
    }
  }
  if (Object.keys(errs).length) rowErrors.value[i] = errs
  else delete rowErrors.value[i]
}

function validateAll() {
  rowErrors.value = {}
  localRows.value.forEach((r, i) => validateRow(r, i))
  return invalidCount.value
}

watch(
  () => props.modelValue,
  (incoming) => {
    if (incoming && Array.isArray(incoming) && incoming.length && !localRows.value.length) {
      localRows.value = incoming.map((r) => ({ ...r }))
      validateAll()
    }
  },
  { immediate: true }
)

watch(localRows, () => validateAll(), { deep: true })

// ===== Paste handling =====
function onPaste(event) {
  if (props.disabled) return
  const clip = event.clipboardData?.getData('text/plain') ?? ''
  if (!clip || !clip.includes('\t')) return  // not TSV; let default behaviour proceed
  event.preventDefault()
  const { headers, rows, headerDetected } = parseTsv(clip)
  if (!rows.length) return
  if (headerDetected && headers.length) {
    pendingHeaders.value = headers
    pendingRows.value = rows
    pendingMapping.value = suggestMapping(headers, props.params)
    mappingOpen.value = true
  } else {
    // No header → fill directly with whatever columns the paste contained,
    // mapped to params in declaration order.
    const direct = suggestMapping(headers.length
      ? headers
      : paramColumns.value.slice(0, rows[0].length).map((p) => p.name), props.params)
    const mapping = direct.map((d, i) => ({ column: d.column || `列${i + 1}`, param: d.param }))
    fillFromMapping(mapping, rows)
  }
}

function onMappingConfirm(mapping) {
  fillFromMapping(mapping, pendingRows.value)
}

function fillFromMapping(mapping, rows) {
  const objs = applyMapping(rows, mapping)
  // append after existing rows (paste accumulates) unless editor was empty.
  if (localRows.value.length === 0) localRows.value = objs
  else localRows.value = [...localRows.value, ...objs]
  ElMessage.success(`已从粘贴填充 ${objs.length} 行`)
  validateAll()
  emitRows()
}

defineExpose({
  /** Insert text programmatically (used when JSON-per-line fallback runs). */
  appendText(text) {
    const lines = (text || '').split(/\r?\n/).filter((l) => l.trim())
    for (const line of lines) {
      try {
        const o = JSON.parse(line.trim())
        if (o && typeof o === 'object' && !Array.isArray(o)) localRows.value.push(o)
      } catch { /* ignore non-JSON line */ }
    }
    validateAll()
    emitRows()
  },
  validate: validateAll,
  /** Only rows with no errors. */
  validRows() {
    return localRows.value.filter((_, i) => !rowErrors.value[i] || !Object.keys(rowErrors.value[i]).length)
  },
  /** Paste handler the caller can wire to a hidden textarea. */
  onPaste
})
</script>

<style scoped>
.sb-batch-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sb-batch-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.sb-batch-actions { display: flex; gap: 8px; align-items: center; }
.sb-batch-banner { margin: 0; padding: 6px 10px; }
.sb-batch-banner :deep(.el-alert__content) { padding: 0 4px; }
.sb-batch-table :deep(.sb-row-invalid) > td { background: #fef2f2 !important; }
.sb-batch-table :deep(.el-input.is-invalid .el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
.sb-row-errors { display: flex; flex-wrap: wrap; gap: 4px; }
.sb-row-error-tag {
  display: inline-block;
  font-size: 11px;
  background: #fee2e2;
  color: #b91c1c;
  padding: 1px 6px;
  border-radius: 3px;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sb-row-ok { color: var(--el-color-success); font-weight: 700; }
</style>
