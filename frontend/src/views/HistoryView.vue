<!--
  HistoryView — execution history with filters and rerun.
  Filters (top bar):
    - Status chips: 全部 / 成功 / 失败 / 超时
    - Script dropdown, Tenant dropdown
    - Keyword (substring against script/tenant name)
  Default limit 100 rows.

  Row click: opens detail drawer with three sections:
    - 基础 (status card + meta)
    - 参数 (key/value)
    - stdout / stderr (terminal-style boxes with copy/download)
  Footer of detail drawer: 再次执行 (opens ExecuteView with rerun payload), 复制参数, 关闭.
-->
<template>
  <div>
    <div class="sb-header">
      <div>
        <h2 class="sb-page-title">执行历史</h2>
        <p class="sb-page-sub">最近 {{ rows.length }} 条执行记录（默认 100 条上限）。</p>
      </div>
      <el-button :icon="Refresh" plain @click="refresh" :loading="loading">刷新</el-button>
    </div>

    <!-- Filter bar -->
    <div class="sb-card sb-filter">
      <div class="sb-filter-row">
        <el-radio-group v-model="status" size="default" @change="refresh">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="success">成功</el-radio-button>
          <el-radio-button value="failed">失败</el-radio-button>
          <el-radio-button value="timeout">超时</el-radio-button>
        </el-radio-group>
      </div>
      <div class="sb-filter-row">
        <el-select
          v-model="scriptId" placeholder="按脚本筛选" clearable filterable
          style="width: 220px" @change="refresh"
        >
          <el-option v-for="s in scripts" :key="s.id"
            :label="s.displayName || s.name" :value="s.id" />
        </el-select>
        <el-select
          v-model="tenantId" placeholder="按租户筛选" clearable filterable
          style="width: 200px" @change="refresh"
        >
          <el-option v-for="t in tenants" :key="t.id"
            :label="t.name" :value="t.id" />
        </el-select>
        <el-input
          v-model="keyword" placeholder="搜索脚本/租户名称" clearable
          style="width: 240px"
          @keyup.enter="refresh"
          @clear="refresh"
        />
        <el-button type="primary" plain @click="refresh">应用</el-button>
      </div>
    </div>

    <el-table :data="rows" v-loading="loading" class="sb-card" stripe @row-click="openDetail">
      <el-table-column label="#" width="70" prop="id" />
      <el-table-column label="脚本" min-width="200">
        <template #default="{ row }">
          <div class="sb-name-cell">
            <span class="sb-name-main">{{ row.scriptName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="租户" min-width="140">
        <template #default="{ row }">
          <span class="muted">{{ row.tenantName || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="160" align="left">
        <template #default="{ row }">
          <el-tag
            size="small" :type="tagType(row)" disable-transitions effect="plain"
          >{{ STATUS_LABEL[statusOf(row)] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="退出码" width="80" align="right">
        <template #default="{ row }">
          <span class="mono">{{ row.exitCode ?? '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="110" align="right">
        <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
      </el-table-column>
      <el-table-column label="开始时间" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.startTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click.stop="openDetail(row)">详情</el-button>
          <el-button size="small" type="primary" plain :icon="RefreshRight" @click.stop="rerun(row)">重跑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer
      v-model="detailOpen"
      :direction="'rtl'"
      :size="'640px'"
      :show-close="false"
      class="sb-exec-drawer"
    >
      <template #header>
        <div class="sb-drawer-header">
          <div>
            <div class="sb-drawer-title">执行详情 #{{ current?.id }}</div>
            <div v-if="current" class="sb-exec-sub">
              <span>{{ current.scriptName }}</span>
              <span class="dot">·</span>
              <span>{{ current.tenantName }}</span>
              <span class="dot">·</span>
              <span>{{ formatDateTime(current.startTime) }}</span>
            </div>
          </div>
          <el-button text :icon="Close" @click="detailOpen = false" />
        </div>
      </template>

      <template v-if="current">
        <div class="sb-status-card" :class="statusClass(current)">
          <div class="sb-status-icon">
            <el-icon :size="28"><component :is="statusIcon(current)" /></el-icon>
          </div>
          <div class="sb-status-text">
            <div class="sb-status-headline">{{ STATUS_LABEL[statusOf(current)] }}</div>
            <div class="sb-status-sub">
              <span>耗时 {{ formatDuration(current.durationMs) }}</span>
              <span class="dot">·</span>
              <span>Exit {{ current.exitCode ?? '-' }}</span>
              <span v-if="current.endTime" class="dot">·</span>
              <span v-if="current.endTime">{{ formatDateTime(current.endTime) }}</span>
            </div>
          </div>
        </div>

        <el-tabs v-model="tab">
          <el-tab-pane label="参数" name="params">
            <div class="sb-log-toolbar">
              <el-button size="small" :icon="DocumentCopy" @click="copyParams">复制参数</el-button>
            </div>
            <pre class="sb-log">{{ paramsPretty }}</pre>
          </el-tab-pane>
          <el-tab-pane label="stdout" name="stdout">
            <div class="sb-log-toolbar">
              <el-button size="small" :icon="DocumentCopy" @click="copy(stdout)">复制</el-button>
              <el-button size="small" :icon="Download" @click="download('stdout', stdout)">下载</el-button>
            </div>
            <pre class="sb-log" v-if="stdout">{{ stdout }}</pre>
            <div v-else class="sb-log sb-log-empty">无 stdout 输出</div>
          </el-tab-pane>
          <el-tab-pane label="stderr" name="stderr">
            <div class="sb-log-toolbar">
              <el-button size="small" :icon="DocumentCopy" @click="copy(stderr)">复制</el-button>
              <el-button size="small" :icon="Download" @click="download('stderr', stderr)">下载</el-button>
            </div>
            <pre class="sb-log" v-if="stderr">{{ stderr }}</pre>
            <div v-else class="sb-log sb-log-empty">无 stderr 输出</div>
          </el-tab-pane>
        </el-tabs>
      </template>

      <template #footer>
        <div class="sb-exec-footer">
          <el-button @click="detailOpen = false">关闭</el-button>
          <div class="right">
            <el-button :icon="DocumentCopy" @click="copyParams">复制参数</el-button>
            <el-button :icon="RefreshRight" type="primary" @click="rerun(current)">再次执行</el-button>
          </div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Refresh, RefreshRight, Close, DocumentCopy, Download,
  CircleCheck, CircleClose, WarningFilled
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listHistory, getHistory } from '../api/history'
import { readStdout, readStderr } from '../api/executions'
import { listScripts } from '../api/scripts'
import { listTenants } from '../api/tenants'
import { formatDateTime, formatDuration, parseParamsJson } from '../utils/format'
import { STATUS, STATUS_LABEL, STATUS_TAG_TYPE, statusOfHistory } from '../utils/labels'

const router = useRouter()
const rows = ref([])
const scripts = ref([])
const tenants = ref([])
const loading = ref(false)

const status = ref('')
const scriptId = ref(null)
const tenantId = ref(null)
const keyword = ref('')

const detailOpen = ref(false)
const current = ref(null)
const stdout = ref('')
const stderr = ref('')
const tab = ref('params')

async function refresh() {
  loading.value = true
  try {
    const params = { limit: 200 }
    if (status.value) params.status = status.value
    if (scriptId.value) params.scriptId = scriptId.value
    if (tenantId.value) params.tenantId = tenantId.value
    if (keyword.value && keyword.value.trim()) params.keyword = keyword.value.trim()
    rows.value = (await listHistory(params)) || []
  } finally { loading.value = false }
}

function statusOf(h) { return statusOfHistory(h) }
function tagType(h) { return STATUS_TAG_TYPE[statusOf(h)] }

function statusClass(h) {
  switch (statusOf(h)) {
    case STATUS.SUCCESS: return 'ok'
    case STATUS.TIMEOUT: return 'warn'
    case STATUS.FAILED:  return 'fail'
    default: return ''
  }
}

function statusIcon(h) {
  switch (statusOf(h)) {
    case STATUS.SUCCESS: return CircleCheck
    case STATUS.TIMEOUT: return WarningFilled
    case STATUS.FAILED:  return CircleClose
    default: return CircleCheck
  }
}

const paramsPretty = () => {
  const p = parseParamsJson(current.value?.parametersJson)
  return Object.keys(p).length ? JSON.stringify(p, null, 2) : '(无参数)'
}

async function openDetail(row) {
  current.value = row
  stdout.value = ''
  stderr.value = ''
  tab.value = 'params'
  detailOpen.value = true
  try { current.value = await getHistory(row.id) } catch { /* keep original */ }
  try { stdout.value = (await readStdout(row.id)) || '' } catch {}
  try { stderr.value = (await readStderr(row.id)) || '' } catch {}
}

function rerun(row) {
  if (!row) return
  // Navigate to ExecuteView with a one-shot rerun payload that ExecuteView consumes
  // from query/router state. Use a tiny sessionStorage handoff to keep the URL clean.
  const payload = {
    scriptId: row.scriptId,
    tenantId: row.tenantId,
    params: parseParamsJson(row.parametersJson)
  }
  sessionStorage.setItem('sb.rerun', JSON.stringify(payload))
  detailOpen.value = false
  router.push({ name: 'execute' })
}

function copyParams() {
  const p = parseParamsJson(current.value?.parametersJson)
  const text = Object.keys(p).length ? JSON.stringify(p, null, 2) : ''
  if (!text) return ElMessage.warning('无可复制的参数')
  navigator.clipboard?.writeText(text).then(
    () => ElMessage.success('已复制'),
    () => ElMessage.error('复制失败')
  )
}

function copy(text) {
  if (!text) return ElMessage.warning('没有内容可以复制')
  navigator.clipboard?.writeText(text).then(
    () => ElMessage.success('已复制'),
    () => ElMessage.error('复制失败')
  )
}

function download(name, text) {
  if (!text) return ElMessage.warning('没有内容可以下载')
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${name}-${current.value?.id || 'history'}.log`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

onMounted(async () => {
  const [s, t] = await Promise.all([listScripts().catch(() => []), listTenants().catch(() => [])])
  scripts.value = s || []
  tenants.value = t || []
  await refresh()
})
</script>

<style scoped>
.sb-filter { padding: 12px 16px; margin-bottom: 12px; }
.sb-filter-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.sb-filter-row:last-child { margin-bottom: 0; }

.sb-name-main { font-weight: 500; font-size: 13.5px; }

.sb-drawer-title { font-size: 16px; font-weight: 600; }
</style>