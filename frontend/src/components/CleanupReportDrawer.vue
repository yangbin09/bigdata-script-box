<!--
  CleanupReportDrawer — shows what actually happened (per spec 二十二).
  Four category rows (deleted / skipped / failed) plus bytes freed,
  elapsed time, and a list of failures.
-->
<template>
  <el-drawer
    :model-value="open"
    direction="rtl"
    size="720px"
    :show-close="false"
    :destroy-on-close="false"
    class="sb-exec-drawer"
    @update:model-value="onClose"
  >
    <template #header>
      <div class="drawer-head">
        <div>
          <div class="drawer-title">清理报告</div>
          <div class="drawer-sub" :class="resultClass">{{ resultLabel }}</div>
        </div>
        <el-button text :icon="Close" @click="onClose(false)">关闭</el-button>
      </div>
    </template>

    <div v-if="!report" class="empty">
      <p class="muted">无报告</p>
    </div>

    <div v-else class="report-body">
      <el-table :data="rows" stripe size="small" class="report-table">
        <el-table-column label="分类" prop="label" min-width="120" />
        <el-table-column label="删除" prop="deleted" width="80" align="center">
          <template #default="{ row }">
            <span class="strong">{{ row.deleted }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="totals">
        <div class="totals-row">
          <span class="muted">实际释放</span>
          <span class="mono">{{ fmtBytes(report.bytesFreed) }}</span>
        </div>
        <div class="totals-row">
          <span class="muted">跳过总数</span>
          <span class="mono">{{ report.skippedCount ?? report.skipped?.length ?? 0 }}</span>
        </div>
        <div class="totals-row">
          <span class="muted">失败总数</span>
          <span class="mono">{{ report.failedCount ?? report.failed?.length ?? 0 }}</span>
        </div>
        <div class="totals-row">
          <span class="muted">开始</span>
          <span class="mono">{{ formatTime(report.startedAtIso) }}</span>
        </div>
        <div class="totals-row">
          <span class="muted">结束</span>
          <span class="mono">{{ formatTime(report.finishedAtIso) }}</span>
        </div>
        <div class="totals-row">
          <span class="muted">耗时</span>
          <span class="mono">{{ ((report.elapsedMs || 0) / 1000).toFixed(2) }}s</span>
        </div>
      </div>

      <div v-if="report.skipped && report.skipped.length" class="sub-block">
        <div class="sub-title">跳过的项</div>
        <ul class="sub-list">
          <li v-for="(line, i) in report.skipped" :key="'sk-' + i" class="mono small">{{ line }}</li>
        </ul>
      </div>

      <div v-if="report.failed && report.failed.length" class="sub-block">
        <div class="sub-title">失败的项</div>
        <ul class="sub-list">
          <li v-for="(f, i) in report.failed" :key="'f-' + i" class="mono small">
            <span class="path">{{ f.path }}</span> — <span class="reason">{{ f.reason }}</span>
          </li>
        </ul>
      </div>

      <div v-if="report.message" class="sub-block">
        <div class="sub-title">说明</div>
        <pre class="msg">{{ report.message }}</pre>
      </div>
    </div>

    <template #footer>
      <div class="sb-exec-footer">
        <el-button @click="onClose(false)">关闭</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed } from 'vue'
import { Close } from '@element-plus/icons-vue'
import { formatBytes as fmtBytes, formatTimestamp as formatTime } from '../utils/format'
import { cleanupResultLabel, cleanupResultClass } from '../utils/cleanup'

const props = defineProps({
  open: { type: Boolean, default: false },
  report: { type: Object, default: null }
})
const emit = defineEmits(['update:open'])

function onClose(v) { emit('update:open', v) }

const resultLabel = computed(() => {
  const r = props.report?.result
  if (!r) return '已完成'
  if (r === 'FAILED') return '清理失败'
  return `清理完成（${cleanupResultLabel(r)}）`
})

const resultClass = computed(() => cleanupResultClass(props.report?.result))

// The report has no per-category skipped/failed split (the executor records
// them as flat lists), so the table only carries the deletion counts; the
// skipped/failed totals are shown below.
const rows = computed(() => {
  if (!props.report) return []
  return [
    { key: 'exec',  label: '执行目录', deleted: props.report.executionDeleted || 0 },
    { key: 'art',   label: '产物文件', deleted: props.report.artifactDeleted  || 0 },
    { key: 'log',   label: '日志',     deleted: props.report.logDeleted       || 0 },
    { key: 'hist',  label: '历史',     deleted: props.report.historyDeleted   || 0 }
  ]
})
</script>

<style scoped>
.drawer-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; width: 100%; }
.drawer-title { font-weight: 600; font-size: 16px; }
.drawer-sub { font-size: 12.5px; margin-top: 2px; }
.sub-success { color: #15803d; }
.sub-warning { color: #b45309; }
.sub-danger  { color: #b91c1c; }

.empty { padding: 40px; text-align: center; }
.report-body { display: flex; flex-direction: column; gap: 14px; padding-bottom: 16px; }

.report-table { border-radius: 6px; }

.totals {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px 18px;
  background: var(--sb-bg-panel);
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 12px 16px;
}
.totals-row { display: flex; justify-content: space-between; font-size: 13px; }

.sub-block {
  background: var(--sb-bg-panel);
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 10px 14px;
}
.sub-title { font-weight: 600; font-size: 13px; margin-bottom: 6px; }
.sub-list { margin: 0; padding-left: 18px; max-height: 200px; overflow: auto; }
.sub-list li { padding: 2px 0; line-height: 1.5; }

.path { color: var(--sb-text-1); }
.reason { color: #b91c1c; }

.msg {
  margin: 0;
  font-family: var(--sb-mono);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sb-text-2);
}

.strong { font-weight: 600; color: var(--sb-text-1); }
.warn-text { color: #b45309; font-weight: 600; }
.danger-text { color: #b91c1c; font-weight: 600; }
.muted { color: var(--sb-text-muted); }
.mono { font-family: var(--sb-mono); font-size: 12.5px; }
.small { font-size: 12px; }
</style>