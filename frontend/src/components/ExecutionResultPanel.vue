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
          <component :is="statusIconComp" />
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

    <!-- V3 (PR-7): 失败行动入口（按 status 分流） -->
    <div v-if="failureActions.length" class="sb-failure-actions">
      <el-alert
        v-for="(act, i) in failureActions"
        :key="i"
        :type="act.tone"
        :title="act.title"
        :description="act.description"
        show-icon
        :closable="false"
      >
        <template #default>
          <div class="sb-failure-row">
            <span>{{ act.description }}</span>
            <el-button v-for="btn in act.buttons" :key="btn.label" size="small"
              :type="btn.tone || 'primary'" @click="btn.onClick">
              {{ btn.label }}
            </el-button>
          </div>
        </template>
      </el-alert>
    </div>

    <!-- V3 (PR-7): 摘要（result.json 优先）+ 指标磁贴 -->
    <section v-if="metrics.length || warningLines.length" class="sb-summary-block">
      <div v-if="metrics.length" class="sb-metrics">
        <div v-for="m in metrics" :key="m.key" class="sb-metric-tile">
          <div class="sb-metric-label">{{ m.label }}</div>
          <div class="sb-metric-value" :class="m.tone">{{ m.value }}</div>
          <div v-if="m.unit" class="sb-metric-unit">{{ m.unit }}</div>
        </div>
      </div>
      <div v-if="warningLines.length" class="sb-warnings">
        <div class="sb-warnings-head">
          <el-icon><WarningFilled /></el-icon>
          <span>异常/告警 ({{ warningLines.length }})</span>
        </div>
        <ul class="sb-warning-list">
          <li v-for="(line, i) in warningLines.slice(0, 5)" :key="i"
            :class="line.tone">
            <span class="sb-warn-tag">{{ line.tag }}</span>
            <span class="sb-warn-text">{{ line.text }}</span>
          </li>
        </ul>
        <div v-if="warningLines.length > 5" class="muted">
          还有 {{ warningLines.length - 5 }} 条 → 切换到 stderr 标签查看
        </div>
      </div>
    </section>

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
import { useRouter } from 'vue-router'
import { RefreshRight, EditPen, Clock, Document, Download, WarningFilled } from '@element-plus/icons-vue'
import { formatDateTime, formatDuration, formatBytes, parseParamsJson } from '../utils/format'
import { STATUS, STATUS_LABEL, STATUS_TAG_TYPE, STATUS_CLASS, STATUS_HEADLINE, statusOfHistory } from '../utils/labels'
import { statusIcon } from '../utils/status'
import { copyText, downloadText } from '../utils/clipboard'
import LogPane from './LogPane.vue'
import { listArtifacts, artifactDownloadUrl, readResult } from '../api/executions'

const router = useRouter()

const props = defineProps({
  history: { type: Object, required: true },
  logStdout: { type: String, default: '' },
  logStderr: { type: String, default: '' }
})

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
    // listArtifacts() already resolves the payload (the artifact array).
    const data = await listArtifacts(props.history.id)
    artifacts.value = Array.isArray(data) ? data : []
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
const statusClass = computed(() => STATUS_CLASS[status.value] ?? '')
const statusIconComp = computed(() => statusIcon(status.value))
const statusHeadline = computed(() => STATUS_HEADLINE[status.value] || '执行完成')

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
    // readResult() already resolves the parsed payload (or null when the
    // script wrote no result.json).
    const obj = await readResult(id)
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
  copyText(text, '没有内容可以复制')
}

function download(name, text) {
  downloadText(`${name}-${props.history.id}.log`, text, '没有内容可以下载')
}

// V3 (PR-7): 从 result.json 抽数字字段做指标磁贴
const _parsedResult = computed(() => {
  if (!resultJsonText.value) return null
  try { return JSON.parse(resultJsonText.value) } catch { return null }
})
const metrics = computed(() => {
  const obj = _parsedResult.value
  if (!obj || typeof obj !== 'object') return []
  const out = []
  for (const [k, v] of Object.entries(obj)) {
    if (typeof v === 'number' && Number.isFinite(v)) {
      const unit = /ms|millisec|time|dur/i.test(k) ? 'ms'
        : /size|bytes?|mem/i.test(k) ? 'bytes'
        : /count|total|num|rows/i.test(k) ? ''
        : ''
      let tone = ''
      if (/error|fail/i.test(k)) tone = 'danger'
      else if (/warn|skip/i.test(k)) tone = 'warning'
      else if (/ok|success/i.test(k)) tone = 'success'
      out.push({
        key: k,
        label: k,
        value: v >= 1000 ? Math.round(v * 100) / 100 : v,
        unit,
        tone
      })
    }
  }
  return out.slice(0, 12)
})

// V3 (PR-7): 从 stderr 抽告警 / 异常行
const warningLines = computed(() => {
  const text = props.logStderr || ''
  if (!text) return []
  const out = []
  const lines = text.split('\n')
  for (const line of lines) {
    const lower = line.toLowerCase()
    if (!line.trim()) continue
    if (/\b(error|exception|failed|fatal)\b/.test(lower)) {
      out.push({ tag: 'ERROR', text: line.trim().slice(0, 240), tone: 'danger' })
    } else if (/\b(warn|warning|deprecat)\b/.test(lower)) {
      out.push({ tag: 'WARN', text: line.trim().slice(0, 240), tone: 'warning' })
    }
    if (out.length >= 50) break
  }
  return out
})

// V3 (PR-7): 按 status 分流的失败行动入口
const failureActions = computed(() => {
  const st = status.value
  const h = props.history
  const acts = []
  if (st === 'TIMEOUT' || h.timeout) {
    acts.push({
      tone: 'warning',
      title: '执行超时',
      description: `脚本运行超过 ${h.scriptName ? '' : ''}设定的超时时间。可加大 timeout 后重试。`,
      buttons: [
        { label: '调整超时后重试', tone: 'primary', onClick: () => emitWith('rerun') }
      ]
    })
  }
  if (st === 'PRECHECK_FAILED') {
    acts.push({
      tone: 'error',
      title: '执行前检查未通过',
      description: '环境（Kerberos、命令、文件、目录）校验失败。请回到脚本编辑页检查 precheck 配置。',
      buttons: [
        { label: '查看脚本', tone: 'primary', onClick: () =>
            router.push({ name: 'script-edit', query: { id: h.scriptId } }) }
      ]
    })
  }
  if (st === 'AUTH_FAILED') {
    acts.push({
      tone: 'error',
      title: '认证失败',
      description: `租户「${h.tenantName}」的 kinit 失败。请检查 Keytab / Principal / 时钟同步。`,
      buttons: [
        { label: '前往租户测试', tone: 'primary', onClick: () =>
            router.push({ name: 'tenants', query: { tenant: h.tenantId } }) }
      ]
    })
  }
  if (st === 'INTERRUPTED') {
    acts.push({
      tone: 'info',
      title: '执行被中断',
      description: h.interruptedReason
        ? `原因：${h.interruptedReason}`
        : '可能原因：进程重启 / 服务异常退出。',
      buttons: [
        { label: '再次执行', tone: 'primary', onClick: () => emitWith('rerun') }
      ]
    })
  }
  if (st === 'FAILED' && !acts.length) {
    acts.push({
      tone: 'error',
      title: '执行失败',
      description: h.errorMessage ? `原因：${h.errorMessage}` : '请查看 stderr 日志。',
      buttons: [
        { label: '查看 stderr', tone: 'primary', onClick: () => { activeTab.value = 'stderr' } }
      ]
    })
  }
  return acts
})
function emitWith(name) { emit(name) }
const emit = defineEmits(['rerun', 'edit', 'view-history'])
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

/* V3 (PR-7): 失败行动入口 */
.sb-failure-actions { margin: 12px 0; display: flex; flex-direction: column; gap: 8px; }
.sb-failure-row {
  display: flex; align-items: center; justify-content: space-between;
  gap: 8px; flex-wrap: wrap;
}

/* V3 (PR-7): 摘要 + 指标 + 告警 */
.sb-summary-block { margin: 12px 0; display: flex; flex-direction: column; gap: 10px; }
.sb-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 8px;
}
.sb-metric-tile {
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 8px 10px;
  background: #fafbfc;
  text-align: left;
}
.sb-metric-label { font-size: 11px; color: var(--sb-text-2); text-transform: uppercase; letter-spacing: 0.5px; }
.sb-metric-value { font-size: 18px; font-weight: 700; margin-top: 2px; }
.sb-metric-value.danger { color: var(--el-color-danger); }
.sb-metric-value.warning { color: var(--el-color-warning); }
.sb-metric-value.success { color: var(--el-color-success); }
.sb-metric-unit { font-size: 11px; color: var(--sb-text-2); }

.sb-warnings {
  border: 1px solid #fed7aa;
  background: #fff7ed;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
}
.sb-warnings-head {
  display: flex; align-items: center; gap: 6px;
  font-weight: 600; color: #9a3412;
  margin-bottom: 6px;
}
.sb-warning-list { margin: 0; padding-left: 0; list-style: none; }
.sb-warning-list li {
  display: flex; gap: 6px;
  padding: 3px 0;
  font-family: var(--el-font-family-monospace, monospace);
}
.sb-warn-tag {
  flex: 0 0 auto;
  font-weight: 700;
  font-size: 10px;
  padding: 0 4px;
  border-radius: 3px;
  color: #fff;
}
.sb-warn-tag.warning { background: #f59e0b; }
.sb-warn-tag.danger { background: #ef4444; }
.sb-warn-text { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>