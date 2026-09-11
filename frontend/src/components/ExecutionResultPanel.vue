<!--
  ExecutionResultPanel — shown inside the drawer after a run completes.
  - Status card (success / failed / timeout) with elapsed time + exit code.
  - Tabs: 结果 / stdout / stderr / 参数.
    V2: stdout/stderr tabs gained search (regex or plain), highlight of matches,
    quick chips (ERROR / WARN / Exception / Caused by / FAILED), and context
    lines (0 / 3 / 5 / 10). The pre-download strip trims the visible content
    to whatever the user is currently looking at, so big logs don't all hit
    disk by accident.
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
        <pre class="sb-log" v-if="resultJsonText">{{ resultJsonText }}</pre>
        <pre class="sb-log" v-else-if="summaryText">{{ summaryText }}</pre>
        <div v-else class="sb-log sb-log-empty">执行完成，无附加输出。</div>
      </el-tab-pane>
      <el-tab-pane label="stdout" name="stdout">
        <LogPane
          :text="logStdout"
          stream-name="stdout"
          :execution-id="history.id"
          @copy="copy"
          @download="download"
        />
      </el-tab-pane>
      <el-tab-pane label="stderr" name="stderr">
        <LogPane
          :text="logStderr"
          stream-name="stderr"
          :execution-id="history.id"
          @copy="copy"
          @download="download"
        />
      </el-tab-pane>
      <el-tab-pane label="参数" name="params">
        <pre class="sb-log">{{ paramsPretty }}</pre>
      </el-tab-pane>
      <el-tab-pane :label="`产物 (${artifacts.length})`" name="artifacts">
        <div v-if="artifactsLoading" class="sb-log sb-log-empty">加载中...</div>
        <div v-else-if="!artifacts.length" class="sb-log sb-log-empty">
          脚本未在 <code>$ARTIFACT_DIR</code> 下写入任何文件。
        </div>
        <div v-else class="sb-artifacts">
          <div
            v-for="a in artifacts"
            :key="a.id"
            class="sb-artifact-row"
          >
            <div class="sb-artifact-name">
              <el-icon><Document /></el-icon>
              <span :title="a.name">{{ shortName(a.name) }}</span>
              <el-tag size="small" effect="plain" disable-transitions>{{ a.mimeType }}</el-tag>
            </div>
            <div class="sb-artifact-meta">
              {{ formatBytes(a.sizeBytes) }}
              <span v-if="a.sha256" class="dot">·</span>
              <code v-if="a.sha256" class="sb-artifact-sha">{{ a.sha256.slice(0, 12) }}…</code>
            </div>
            <a
              class="sb-artifact-dl"
              :href="artifactDownloadUrl(history.id, a.name)"
              :download="shortName(a.name)"
            >
              <el-icon><Download /></el-icon>
              下载
            </a>
          </div>
        </div>
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
  RefreshRight, EditPen, Clock, Document, Download
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { formatDateTime, formatDuration, formatBytes, parseParamsJson } from '../utils/format'
import { STATUS, STATUS_LABEL, STATUS_TAG_TYPE, statusOfHistory } from '../utils/labels'
import LogPane from './LogPane.vue'
import { listArtifacts, artifactDownloadUrl, readResult } from '../api/executions'

const props = defineProps({
  history: { type: Object, required: true },
  logStdout: { type: String, default: '' },
  logStderr: { type: String, default: '' }
})

defineEmits(['rerun', 'edit', 'view-history'])

const activeTab = ref('result')

// V2: artifacts panel state. Lazily fetched when the panel mounts so
// running executions don't pay the cost; refreshes when the history
// row's id changes (e.g. user re-runs).
const artifacts = ref([])
const artifactsLoading = ref(false)
async function refreshArtifacts() {
  if (!props.history || props.history.id == null) {
    artifacts.value = []
    return
  }
  artifactsLoading.value = true
  try {
    const data = await listArtifacts(props.history.id)
    artifacts.value = Array.isArray(data?.data) ? data.data : []
  } catch (_) {
    artifacts.value = []
  } finally {
    artifactsLoading.value = false
  }
}
watch(() => props.history?.id, refreshArtifacts, { immediate: true })

function shortName(name) {
  if (!name) return ''
  const i = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'))
  return i >= 0 ? name.substring(i + 1) : name
}

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

// V2: load result.json if the script wrote one. Shown above the summary
// in the 结果 tab — users post-run see structured output first, fall
// back to the derived summary when nothing was written.
const resultJsonText = ref('')
const resultJsonLoadedFor = ref(null)
watch(() => props.history?.id, async (id) => {
  resultJsonText.value = ''
  resultJsonLoadedFor.value = null
  if (id == null) return
  try {
    const r = await readResult(id)
    const obj = r?.data
    resultJsonText.value = obj == null ? '' : JSON.stringify(obj, null, 2)
  } catch (_) { resultJsonText.value = '' }
  resultJsonLoadedFor.value = id
}, { immediate: true })

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
.sb-artifacts {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.sb-artifact-row {
  display: grid;
  grid-template-columns: 1fr auto auto;
  gap: 12px;
  align-items: center;
  padding: 8px 12px;
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  background: var(--sb-bg-soft);
}
.sb-artifact-name {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.sb-artifact-name > span {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sb-artifact-meta {
  font-size: 12px;
  color: var(--sb-text-muted);
  display: flex;
  align-items: center;
  gap: 4px;
}
.sb-artifact-sha {
  font-family: var(--sb-mono);
  font-size: 11px;
}
.sb-artifact-dl {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  text-decoration: none;
  color: var(--el-color-primary);
  font-size: 13px;
}
.sb-artifact-dl:hover { text-decoration: underline; }
</style>