<!--
  ExecutionResultDialog — status card on top, Tabs for stdout/stderr/params below.
  Kept narrow, never raw-dumps logs into the page chrome.
-->
<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="(v) => emit('update:modelValue', v)"
    :title="title"
    width="780px"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <template v-if="history">
      <div class="sb-status-card" :class="statusClass">
        <div class="sb-status-icon">
          <el-icon :size="28">
            <component :is="statusIcon" />
          </el-icon>
        </div>
        <div class="sb-status-text">
          <div class="sb-status-headline">{{ statusHeadline }}</div>
          <div class="sb-status-sub">
            <span>脚本 {{ history.scriptName }}</span>
            <span class="dot">·</span>
            <span>租户 {{ history.tenantName }}</span>
            <span class="dot">·</span>
            <span>耗时 {{ formatDuration(history.durationMs) }}</span>
          </div>
        </div>
        <div class="sb-status-tags">
          <el-tag
            size="small"
            :type="history.success ? 'success' : 'danger'"
            disable-transitions
          >exit {{ history.exitCode ?? '-' }}</el-tag>
          <el-tag
            v-if="history.timeout"
            size="small"
            type="warning"
            disable-transitions
          >timeout</el-tag>
        </div>
      </div>

      <el-tabs v-model="activeTab" class="sb-result-tabs">
        <el-tab-pane label="stdout" name="stdout">
          <pre class="sb-log" v-if="logStdout">{{ logStdout }}</pre>
          <div v-else-if="loading" class="sb-log sb-log-empty">加载中…</div>
          <div v-else class="sb-log sb-log-empty">(空)</div>
        </el-tab-pane>
        <el-tab-pane label="stderr" name="stderr">
          <pre class="sb-log" v-if="logStderr">{{ logStderr }}</pre>
          <div v-else-if="loading" class="sb-log sb-log-empty">加载中…</div>
          <div v-else class="sb-log sb-log-empty">(空)</div>
        </el-tab-pane>
        <el-tab-pane label="参数" name="params">
          <pre class="sb-log">{{ paramsPretty }}</pre>
        </el-tab-pane>
      </el-tabs>
    </template>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { CircleClose, CircleCheck, WarningFilled } from '@element-plus/icons-vue'
import { formatDuration, parseParamsJson } from '../utils/format'

const props = defineProps({
  modelValue: Boolean,
  history: Object,
  loading: Boolean,
  logStdout: String,
  logStderr: String
})
const emit = defineEmits(['update:modelValue'])

const activeTab = ref('stdout')

const title = computed(() => {
  if (!props.history) return '执行结果'
  return `执行结果 — ${props.history.scriptName} #${props.history.id}`
})

const statusClass = computed(() => {
  if (!props.history) return ''
  if (props.history.timeout) return 'warn'
  return props.history.success ? 'ok' : 'fail'
})

const statusIcon = computed(() => {
  if (!props.history) return CircleCheck
  if (props.history.timeout) return WarningFilled
  return props.history.success ? CircleCheck : CircleClose
})

const statusHeadline = computed(() => {
  if (!props.history) return ''
  if (props.history.timeout) return '执行超时'
  return props.history.success ? '执行成功' : '执行失败'
})

const paramsPretty = computed(() => {
  if (!props.history) return ''
  const p = parseParamsJson(props.history.parametersJson)
  return Object.keys(p).length ? JSON.stringify(p, null, 2) : '(无参数)'
})

// Default to stderr when failed (more useful), stdout otherwise.
watch(() => props.history, (h) => {
  if (!h) return
  activeTab.value = h.success ? 'stdout' : 'stderr'
})
</script>

<style scoped>
.sb-status-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border-radius: 6px;
  border: 1px solid var(--sb-border);
  background: #f8fafc;
  margin-bottom: 12px;
}
.sb-status-card.ok   { background: #f0fdf4; border-color: #bbf7d0; }
.sb-status-card.fail { background: #fef2f2; border-color: #fecaca; }
.sb-status-card.warn { background: #fffbeb; border-color: #fde68a; }

.sb-status-icon { display: flex; }
.sb-status-card.ok   .sb-status-icon { color: var(--sb-success); }
.sb-status-card.fail .sb-status-icon { color: var(--sb-danger); }
.sb-status-card.warn .sb-status-icon { color: var(--sb-warning); }

.sb-status-text { flex: 1; }
.sb-status-headline { font-size: 15px; font-weight: 600; }
.sb-status-sub {
  font-size: 12.5px;
  color: var(--sb-text-3);
  margin-top: 2px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.sb-status-sub .dot { color: var(--sb-text-3); }

.sb-status-tags { display: flex; gap: 4px; }

.sb-result-tabs { margin-top: 4px; }
.sb-result-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }
</style>