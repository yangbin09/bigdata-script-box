<!--
  ColumnMappingDialog — shown after a user pastes TSV into the batch table.
  For each TSV column, lets them pick which script param it maps to (or skip).
-->
<template>
  <el-dialog
    :model-value="modelValue"
    title="列映射 — 选择 TSV 列对应的脚本参数"
    width="540px"
    :close-on-click-modal="false"
    @update:model-value="(v) => emit('update:modelValue', v)"
    @confirm="onConfirm"
    @closed="reset"
  >
    <div v-if="headers.length" class="sb-mapping-rows">
      <div v-for="(h, i) in headers" :key="i" class="sb-mapping-row">
        <div class="sb-mapping-col">
          <div class="sb-mapping-label">TSV 列</div>
          <el-tag size="small" effect="plain" disable-transitions>{{ h }}</el-tag>
        </div>
        <el-icon class="sb-mapping-arrow"><Right /></el-icon>
        <div class="sb-mapping-col">
          <div class="sb-mapping-label">映射到参数</div>
          <el-select v-model="mapping[i]" filterable clearable size="small"
            placeholder="不映射（跳过）" style="width: 200px">
            <el-option
              v-for="p in paramOptions"
              :key="p.value"
              :label="p.label"
              :value="p.value"
            />
          </el-select>
        </div>
      </div>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" @click="onConfirm">确认填充</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch, computed } from 'vue'
import { Right } from '@element-plus/icons-vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  headers: { type: Array, default: () => [] },
  params: { type: Array, default: () => [] },
  initialMapping: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const mapping = ref([])

const paramOptions = computed(() =>
  props.params.map((p) => ({
    value: p.name,
    label: `${p.label || p.name}${p.required ? ' *' : ''}`
  }))
)

watch(
  () => [props.headers, props.initialMapping, props.modelValue],
  () => {
    if (!props.modelValue) return
    mapping.value = props.headers.map((_, i) => props.initialMapping[i]?.param ?? null)
  },
  { immediate: true }
)

function reset() {
  mapping.value = []
}

function onConfirm() {
  emit('confirm', mapping.value.map((param, i) => ({ column: props.headers[i], param })))
  emit('update:modelValue', false)
}
</script>

<style scoped>
.sb-mapping-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sb-mapping-row {
  display: grid;
  grid-template-columns: 1fr 24px 1fr;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
}
.sb-mapping-col { display: flex; flex-direction: column; gap: 4px; }
.sb-mapping-label {
  font-size: 11px;
  color: var(--sb-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.4px;
}
.sb-mapping-arrow {
  color: var(--sb-text-muted);
  justify-self: center;
}
</style>
