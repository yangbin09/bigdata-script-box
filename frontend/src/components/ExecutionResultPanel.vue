<!--
  ExecutionResultPanel — shown inside the drawer after a run completes.
  - Status card (success / failed / timeout) with elapsed time + exit code.
  - Tabs: 结果 / stdout / stderr / 参数.
  - "再次执行" / "修改参数" / "查看历史" buttons handled by parent via slots/events.
-->
<template>
  <div class="sb-result">
    <div class="sb-status-card" :class="statusClass">
      <div class="sb-status-icon">
        <el-icon :size="28">
          <component :is="statusIcon" />
        </el-icon>
      </div>
      <div class="sb-status-text">
        <div class="sb-status-headline">{{ statusHeadline }}</div>
        <div class="sb-status-sub">
          <span>{{ history.scriptName }}</span>
          <span class="dot">·</span>
          <span>{{ history.tenantName }}</span>
          <span class="dot">·</span>
          <span>耗时 {{ formatDuration(history.durationMs) }}</span>
          <span v-if="history.endTime" class="dot">·</span>
          <span v-if="history.endTime">{{ formatDateTime(history.endTime) }}</span>
        </div>
      </div>
      <div class="sb-status-tags">
        <el-tag size="small" :type="statusTag" disable-transitions>
          {{ statusLabel }}
        </el-tag>
        <el-tag size="small" disable-transitions effect="plain">
          Exit {{ history.exitCode ?? '-' }}
        </el-tag>
        <el-tag v-if="history.timeout" size="small" type="warning" disable-transitions>
          超时
        </el-tag>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="sb-result-tabs">
      <el-tab-pane label="结果" name="result">
        <pre class="sb-log" v-if="summaryText">{{ summaryText }}</pre>
        <div v-else class="sb-log sb-log-empty">执行完成，无附加输出。</div>
      </el-tab-pane>
      <el-tab-pane label="stdout" name="stdout">
        <div class="sb-log-toolbar">
          <el-button size="small" :icon="DocumentCopy" @click="copy(logStdout)">复制</el-button>
          <el-button size="small" :icon="Download" @click="download('stdout', logStdout)">下载</el-button>
        </div>
        <pre class="sb-log" v-if="logStdout">{{ logStdout }}</pre>
        <div v-else class="sb-log sb-log-empty">无 stdout 输出</div>
      </el-tab-pane>
      <el-tab-pane label="stderr" name="stderr">
        <div class="sb-log-toolbar">
          <el-button size="small" :icon="DocumentCopy" @click="copy(logStderr)">复制</el-button>
          <el-button size="small" :icon="Download" @click="download('stderr', logStderr)">下载</el-button>
        </div>
        <pre class="sb-log" v-if="logStderr">{{ logStderr }}</pre>
        <div v-else class="sb-log sb-log-empty">无 stderr 输出</div>
      </el-tab-pane>
      <el-tab-pane label="参数" name="params">
        <pre class="sb-log">{{ paramsPretty }}</pre>
      </el-tab-pane>
    </el-tabs>

    <div class="sb-result-actions">
      <el-button :icon="RefreshRight" type="primary" @click="$emit('rerun')">再次执行</el-button>
      <el-button :icon="EditPen" @click="$emit('edit')">修改参数</el-button>
      <el-button :icon="Clock" @click="$emit('view-history')">查看历史</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import {
  CircleClose, CircleCheck, WarningFilled,
  DocumentCopy, Download, RefreshRight, EditPen, Clock
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { formatDateTime, formatDuration, parseParamsJson } from '../utils/format'
import { STATUS, STATUS_LABEL, STATUS_TAG_TYPE, statusOfHistory } from '../utils/labels'

const props = defineProps({
  history: { type: Object, required: true },
  logStdout: { type: String, default: '' },
  logStderr: { type: String, default: '' }
})

defineEmits(['rerun', 'edit', 'view-history'])

const activeTab = ref('result')

const status = computed(() => statusOfHistory(props.history))
const statusLabel = computed(() => STATUS_LABEL[status.value])
const statusTag = computed(() => STATUS_TAG_TYPE[status.value])

const statusClass = computed(() => {
  switch (status.value) {
    case STATUS.SUCCESS: return 'ok'
    case STATUS.TIMEOUT: return 'warn'
    case STATUS.FAILED:  return 'fail'
    default: return ''
  }
})

const statusIcon = computed(() => {
  switch (status.value) {
    case STATUS.SUCCESS: return CircleCheck
    case STATUS.TIMEOUT: return WarningFilled
    case STATUS.FAILED:  return CircleClose
    default: return CircleCheck
  }
})

const statusHeadline = computed(() => {
  switch (status.value) {
    case STATUS.SUCCESS: return '执行成功'
    case STATUS.TIMEOUT: return '执行超时'
    case STATUS.FAILED:  return '执行失败'
    default: return '执行完成'
  }
})

const summaryText = computed(() => {
  const lines = []
  lines.push(`状态    : ${statusLabel.value}`)
  lines.push(`耗时    : ${formatDuration(props.history.durationMs)}`)
  lines.push(`Exit    : ${props.history.exitCode ?? '-'}`)
  if (props.history.timeout) lines.push('超时    : 是')
  if (props.logStdout && props.logStdout.trim()) {
    const firstLine = props.logStdout.split('\n').find((l) => l.trim()) || ''
    if (firstLine) lines.push(`输出    : ${firstLine}`)
  }
  return lines.join('\n')
})

const paramsPretty = computed(() => {
  const p = parseParamsJson(props.history.parametersJson)
  return Object.keys(p).length ? JSON.stringify(p, null, 2) : '(无参数)'
})

// Default to stderr when failed/timeout (more useful), stdout otherwise.
watch(() => props.history, (h) => {
  if (!h) return
  activeTab.value = (h.timeout || !h.success) ? 'stderr' : 'result'
}, { immediate: true })

function copy(text) {
  if (!text) {
    ElMessage.warning('没有内容可以复制')
    return
  }
  navigator.clipboard?.writeText(text).then(
    () => ElMessage.success('已复制'),
    () => ElMessage.error('复制失败')
  )
}

function download(name, text) {
  if (!text) {
    ElMessage.warning('没有内容可以下载')
    return
  }
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${name}-${props.history.id}.log`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.sb-result-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--sb-border);
}
.sb-result-tabs { margin-top: 4px; }
.sb-result-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }
</style>