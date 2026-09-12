<!--
  ScriptDiffPanel — compare a history row's params against the current script
  schema, surfacing renames, type changes, additions, and removals so the
  user knows what they're about to re-run.

  V3 (PR-9): pure presentation; accepts a `history` (with parametersJson) and
  a `script` (with current params[]) and derives the diff.
-->
<template>
  <div class="sb-diff-panel">
    <div v-if="!hasDiff" class="sb-diff-empty muted">
      <el-icon><Check /></el-icon>
      <span>参数 schema 与历史完全一致，可放心按当前脚本重跑。</span>
    </div>
    <template v-else>
      <el-alert
        :type="additions.length || removals.length ? 'warning' : 'info'"
        :closable="false"
        show-icon
        class="sb-diff-summary"
      >
        <template #title>
          <span>
            共 {{ totalChanges }} 处变更：
            <span v-if="additions.length" class="sb-diff-tag sb-diff-add">+{{ additions.length }} 新增</span>
            <span v-if="removals.length" class="sb-diff-tag sb-diff-del">-{{ removals.length }} 删除</span>
            <span v-if="typeChanges.length" class="sb-diff-tag sb-diff-type">{{ typeChanges.length }} 类型变化</span>
            <span v-if="labelChanges.length" class="sb-diff-tag sb-diff-label">{{ labelChanges.length }} 名称变化</span>
          </span>
        </template>
      </el-alert>
      <ul class="sb-diff-list">
        <li v-for="a in additions" :key="`add-${a.name}`" class="sb-diff-row sb-diff-add-row">
          <el-tag size="small" type="success" disable-transitions effect="plain">新增</el-tag>
          <code class="sb-diff-name">{{ a.name }}</code>
          <span class="muted">{{ a.label }} · {{ a.type }}</span>
        </li>
        <li v-for="r in removals" :key="`del-${r.name}`" class="sb-diff-row sb-diff-del-row">
          <el-tag size="small" type="danger" disable-transitions effect="plain">已删除</el-tag>
          <code class="sb-diff-name">{{ r.name }}</code>
          <span class="muted">历史值：<code>{{ r.historyValue ?? '∅' }}</code></span>
        </li>
        <li v-for="t in typeChanges" :key="`type-${t.name}`" class="sb-diff-row sb-diff-type-row">
          <el-tag size="small" type="warning" disable-transitions effect="plain">类型变化</el-tag>
          <code class="sb-diff-name">{{ t.name }}</code>
          <span class="muted">
            {{ t.fromType }} <el-icon><Right /></el-icon> {{ t.toType }}
            <span v-if="t.fromLabel !== t.toLabel"> · 名称：{{ t.fromLabel }} → {{ t.toLabel }}</span>
          </span>
        </li>
        <li v-for="l in labelChanges" :key="`label-${l.name}`" class="sb-diff-row sb-diff-label-row">
          <el-tag size="small" type="info" disable-transitions effect="plain">名称变化</el-tag>
          <code class="sb-diff-name">{{ l.name }}</code>
          <span class="muted">{{ l.fromLabel }} → {{ l.toLabel }} · 类型不变</span>
        </li>
      </ul>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Check, Right } from '@element-plus/icons-vue'
import { parseParamsJson } from '../utils/format'

const props = defineProps({
  history: { type: Object, required: true },
  script: { type: Object, required: true }
})

const historyParams = computed(() => parseParamsJson(props.history?.parametersJson || '{}'))
const currentParams = computed(() => props.script?.params || [])

const additions = computed(() => {
  const known = new Set(Object.keys(historyParams.value))
  return currentParams.value.filter((p) => p.name && !known.has(p.name))
})

const removals = computed(() => {
  const known = new Set(currentParams.value.map((p) => p.name))
  const out = []
  for (const [k, v] of Object.entries(historyParams.value)) {
    if (!known.has(k)) out.push({ name: k, historyValue: v })
  }
  return out
})

const typeChanges = computed(() => {
  const out = []
  const byName = new Map(currentParams.value.map((p) => [p.name, p]))
  for (const [k, v] of Object.entries(historyParams.value)) {
    const cur = byName.get(k)
    if (!cur) continue
    if (cur.type && v != null && typeof v === 'string') {
      const fromType = guessType(v)
      if (fromType !== cur.type) {
        out.push({
          name: k,
          fromType,
          toType: cur.type,
          fromLabel: cur.label || k,
          toLabel: cur.label || k
        })
      }
    }
  }
  return out
})

const labelChanges = computed(() => {
  // Label rename without type change is rare; detect via current vs
  // history.parametersJson by comparing script schema snapshots if available.
  const snap = props.history?.snapshotJson
  if (!snap) return []
  let snapParams = null
  try {
    const o = typeof snap === 'string' ? JSON.parse(snap) : snap
    snapParams = o?.params && typeof o.params === 'object' ? o.params : null
  } catch { return [] }
  if (!snapParams) return []
  const out = []
  const byName = new Map(currentParams.value.map((p) => [p.name, p]))
  for (const p of currentParams.value) {
    const old = snapParams[p.name]
    if (old && typeof old === 'object' && old.label !== undefined) {
      if (old.label !== p.label) {
        out.push({
          name: p.name,
          fromLabel: old.label || p.name,
          toLabel: p.label || p.name
        })
      }
    }
  }
  return out
})

const totalChanges = computed(() =>
  additions.value.length + removals.value.length + typeChanges.value.length + labelChanges.value.length)

const hasDiff = computed(() => totalChanges.value > 0)

function guessType(v) {
  if (v == null || v === '') return 'unknown'
  if (!Number.isNaN(Number(v)) && v.trim() !== '') return 'number'
  if (/^(true|false)$/i.test(v)) return 'boolean'
  return 'text'
}
</script>

<style scoped>
.sb-diff-panel { display: flex; flex-direction: column; gap: 8px; }
.sb-diff-empty {
  display: flex; align-items: center; gap: 6px;
  padding: 10px 12px;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  border-radius: 6px;
  font-size: 13px;
  color: #047857;
}
.sb-diff-summary { padding: 4px 10px; }
.sb-diff-summary :deep(.el-alert__content) { padding: 0 4px; }
.sb-diff-tag {
  display: inline-block;
  font-size: 11px;
  margin-left: 8px;
  padding: 0 6px;
  border-radius: 3px;
  font-weight: 600;
}
.sb-diff-tag.sb-diff-add { background: #d1fae5; color: #065f46; }
.sb-diff-tag.sb-diff-del { background: #fee2e2; color: #b91c1c; }
.sb-diff-tag.sb-diff-type { background: #fef3c7; color: #92400e; }
.sb-diff-tag.sb-diff-label { background: #dbeafe; color: #1e40af; }

.sb-diff-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.sb-diff-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border: 1px solid var(--sb-border);
  border-radius: 4px;
  font-size: 12px;
}
.sb-diff-row.sb-diff-add-row { border-color: #a7f3d0; background: #f0fdf4; }
.sb-diff-row.sb-diff-del-row { border-color: #fecaca; background: #fef2f2; }
.sb-diff-row.sb-diff-type-row { border-color: #fde68a; background: #fffbeb; }
.sb-diff-row.sb-diff-label-row { border-color: #bfdbfe; background: #eff6ff; }
.sb-diff-name {
  font-family: var(--sb-mono, monospace);
  font-weight: 600;
  font-size: 12px;
}
</style>
