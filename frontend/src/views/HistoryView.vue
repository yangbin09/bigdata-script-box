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
        <p class="sb-page-sub">{{ rows.length > 0 ? `最近 ${rows.length} 条执行记录（最多 200 条）` : '这里会显示你跑过的脚本记录。' }}</p>
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
          <el-radio-button value="cancelled">已取消</el-radio-button>
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
        <!-- V2: date-range filter. from is inclusive, to is exclusive.
             Quick chips: 今天 / 近7天 / 近30天 to skip the calendar. -->
        <el-date-picker
          v-model="dateFrom" type="date" value-format="YYYY-MM-DD"
          placeholder="开始日期" style="width: 140px" clearable
          @change="refresh" />
        <span class="muted">~</span>
        <el-date-picker
          v-model="dateTo" type="date" value-format="YYYY-MM-DD"
          placeholder="结束日期" style="width: 140px" clearable
          @change="refresh" />
        <el-button-group size="small">
          <el-button @click="quickDateRange(1)">今天</el-button>
          <el-button @click="quickDateRange(7)">近7天</el-button>
          <el-button @click="quickDateRange(30)">近30天</el-button>
          <el-button @click="resetDateRange">重置</el-button>
        </el-button-group>
        <el-button @click="resetAllFilters">全部清空</el-button>
        <el-button type="primary" plain @click="refresh">应用</el-button>
      </div>
    </div>

    <el-table :data="rows" v-loading="loading" class="sb-card" stripe @row-click="openDetail">
      <template #empty>
        <el-empty v-if="!loading && !hasFilters" description="还没有执行记录。先去「执行」页面跑一个脚本试试。" />
        <el-empty v-else description="没有匹配当前筛选条件的记录。点击右上角「全部清空」重置筛选。" />
      </template>
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
      <el-table-column label="结果" width="110" align="left">
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
        <div class="sb-status-card" :class="statusClassOf(current)">
          <div class="sb-status-icon">
            <el-icon :size="28"><component :is="statusIconOf(current)" /></el-icon>
          </div>
          <div class="sb-status-text">
            <div class="sb-status-headline">{{ statusHeadlineOf(current) }}</div>
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
            <!-- V3 (PR-9): compare current script schema vs history params
                 so the user knows what changed before re-running. -->
            <div v-if="currentScript" class="sb-diff-block">
              <h4 class="sb-test-section-title">与当前脚本 schema 对比</h4>
              <ScriptDiffPanel :history="current" :script="currentScript" />
            </div>
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
          <!-- V2: structured result.json (written by the script via $RESULT_FILE) -->
          <el-tab-pane label="结果" name="result">
            <div class="sb-log-toolbar">
              <el-button size="small" :icon="Refresh" :loading="resultLoading" @click="loadResult(current?.id, true)">刷新</el-button>
              <el-button size="small" :icon="DocumentCopy" :disabled="!resultText" @click="copy(resultText)">复制</el-button>
              <el-button size="small" :icon="Download" :disabled="!resultText" @click="download('result', resultText)">下载</el-button>
            </div>
            <pre class="sb-log" v-if="resultText">{{ resultText }}</pre>
            <div v-else class="sb-log sb-log-empty">
              {{ resultLoading ? '加载中…' : '未生成 result.json（脚本未写入结构化结果）' }}
            </div>
          </el-tab-pane>
          <!-- V2: artifacts registered by the executor under $ARTIFACT_DIR -->
          <el-tab-pane label="产物" name="artifacts">
            <div class="sb-log-toolbar">
              <el-button size="small" :icon="Refresh" :loading="artifactsLoading" @click="loadArtifacts(current?.id, true)">刷新</el-button>
            </div>
            <el-table v-if="artifacts.length" :data="artifacts" class="sb-card" stripe size="small">
              <el-table-column label="文件名" min-width="220">
                <template #default="{ row }">
                  <span class="mono">{{ row.name }}</span>
                </template>
              </el-table-column>
              <el-table-column label="大小" width="110" align="right">
                <template #default="{ row }">
                  <span class="mono">{{ formatBytes(row.sizeBytes) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" align="right">
                <template #default="{ row }">
                  <el-button size="small" type="primary" plain :icon="Download"
                    @click="downloadArtifact(row)">下载</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div v-else class="sb-log sb-log-empty">
              {{ artifactsLoading ? '加载中…' : '无产物文件（脚本未写入 $ARTIFACT_DIR）' }}
            </div>
          </el-tab-pane>
          <!-- V2: snapshotted body + params captured at the time of the run -->
          <el-tab-pane label="快照" name="snapshot">
            <div class="sb-log-toolbar">
              <el-button size="small" :icon="DocumentCopy" :disabled="!snapshotText" @click="copy(snapshotText)">复制</el-button>
            </div>
            <pre class="sb-log" v-if="snapshotText">{{ snapshotText }}</pre>
            <div v-else class="sb-log sb-log-empty">无快照（本次执行未保留快照）</div>
          </el-tab-pane>
        </el-tabs>
      </template>

      <template #footer>
        <div class="sb-exec-footer">
          <el-button @click="detailOpen = false">关闭</el-button>
          <div class="right">
            <el-button :icon="DocumentCopy" @click="copyParams">复制参数</el-button>
            <!-- V2: "重跑历史" uses the snapshotted body + params, ignoring
                 any edits the script has received since this run. V3 (PR-9):
                 tooltip clarifies this means "restore script body to its
                 state at this run, then execute with the same params". -->
            <el-tooltip
              v-if="current?.snapshotJson"
              content="使用执行时的脚本体+参数；脚本若被改过也会被临时回滚到当时版本"
              placement="top"
            >
              <el-button
                :icon="RefreshLeft"
                :loading="rerunning"
                @click="rerunFromSnapshot(current)"
              >按当前快照重跑 ({{ snapshotDateLabel }})</el-button>
            </el-tooltip>
            <el-tooltip
              content="使用当前脚本体，但参数仍用本次历史的值"
              placement="top"
            >
              <el-button :icon="RefreshRight" type="primary" @click="rerun(current)">使用当前脚本重跑</el-button>
            </el-tooltip>
          </div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref, watch, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  Refresh, RefreshRight, RefreshLeft, Close, DocumentCopy, Download
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listHistory, getHistory } from '../api/history'
import {
  readStdout, readStderr, readResult, rerunExecution,
  listArtifacts, artifactDownloadUrl
} from '../api/executions'
import { listScripts } from '../api/scripts'
import { listTenants } from '../api/tenants'
import { formatDateTime, formatDuration, parseParamsJson, formatBytes, toDateString } from '../utils/format'
import { STATUS_CLASS, STATUS_HEADLINE, STATUS_LABEL, STATUS_TAG_TYPE, statusOfHistory } from '../utils/labels'
import { statusIcon } from '../utils/status'
import { copyText, downloadText } from '../utils/clipboard'
import { setSessionItem, KEYS } from '../utils/storage'
import ScriptDiffPanel from '../components/ScriptDiffPanel.vue'
import { useHistoryViewState } from '../composables/useHistoryViewState'
import { getScript } from '../api/scripts'

const router = useRouter()
const rows = ref([])
const scripts = ref([])
const tenants = ref([])
const loading = ref(false)

const status = ref('')
const scriptId = ref(null)
const tenantId = ref(null)
const keyword = ref('')
// V2: optional date-range filter — yyyy-MM-dd strings; the backend treats
// `from` as inclusive and `to` as exclusive (the day after the last kept day).
const dateFrom = ref('')
const dateTo = ref('')

// True when the user has applied any filter — used by the empty state to
// show a different hint ("reset filters") versus the cold-start hint
// ("run a script first").
const hasFilters = computed(() =>
  !!status.value || scriptId.value != null || tenantId.value != null ||
  (keyword.value && keyword.value.trim()) || !!dateFrom.value || !!dateTo.value)

const detailOpen = ref(false)
const current = ref(null)
// V3 (PR-9): current script schema, fetched when the detail drawer opens.
// Used by ScriptDiffPanel to compare history params vs. current params.
const currentScript = ref(null)

// V3 (PR-9): view state preservation across detail-drawer close cycles.
// We snapshot filters + scrollTop when the drawer closes, then restore
// them after the list re-fetches.
const viewState = useHistoryViewState()
const pendingScrollTop = ref(null)
let detachScroll = null

function snapshotViewState() {
  const el = document.querySelector('.sb-card .el-table__body-wrapper')
  viewState.snapshot({
    status: status.value,
    scriptId: scriptId.value,
    tenantId: tenantId.value,
    keyword: keyword.value,
    dateFrom: dateFrom.value,
    dateTo: dateTo.value,
    page: 1,
    pageSize: 200,
    scrollTop: el ? el.scrollTop || 0 : 0
  })
}

function restoreViewState() {
  const s = viewState.restore()
  if (!s) return
  status.value = s.status || ''
  scriptId.value = s.scriptId ?? null
  tenantId.value = s.tenantId ?? null
  keyword.value = s.keyword || ''
  dateFrom.value = s.dateFrom || ''
  dateTo.value = s.dateTo || ''
  pendingScrollTop.value = s.scrollTop || 0
}
const stdout = ref('')
const stderr = ref('')
const tab = ref('params')

// V2: detail tabs (结果 / 产物 / 快照) — lazy-loaded the first time
// the tab is selected so the initial open isn't blocked on all four reads.
const resultText = ref('')
const resultLoadedFor = ref(null)
const resultLoading = ref(false)
const artifacts = ref([])
const artifactsLoadedFor = ref(null)
const artifactsLoading = ref(false)

async function refresh() {
  loading.value = true
  try {
    // Silently swap if user picked from > to, otherwise the API silently
    // returns nothing and the table looks broken.
    if (dateFrom.value && dateTo.value && dateFrom.value > dateTo.value) {
      const tmp = dateFrom.value
      dateFrom.value = dateTo.value
      dateTo.value = tmp
      ElMessage.info('日期范围已自动调整为：' + dateFrom.value + ' 至 ' + dateTo.value)
    }
    const params = { limit: 200 }
    if (status.value) params.status = status.value
    if (scriptId.value) params.scriptId = scriptId.value
    if (tenantId.value) params.tenantId = tenantId.value
    if (keyword.value && keyword.value.trim()) params.keyword = keyword.value.trim()
    if (dateFrom.value) params.from = dateFrom.value
    if (dateTo.value) params.to = dateTo.value
    rows.value = (await listHistory(params)) || []
    // V3 (PR-9): restore scrollTop if a snapshot existed.
    if (pendingScrollTop.value != null) {
      const top = pendingScrollTop.value
      pendingScrollTop.value = null
      requestAnimationFrame(() => {
        const el = document.querySelector('.sb-card .el-table__body-wrapper')
        if (el && top) el.scrollTop = top
      })
    }
  } catch (_) {
    // The interceptor surfaced the message; keep the previous rows visible.
  } finally { loading.value = false }
}

function resetDateRange() {
  dateFrom.value = ''
  dateTo.value = ''
  refresh()
}

function resetAllFilters() {
  status.value = ''
  scriptId.value = null
  tenantId.value = null
  keyword.value = ''
  dateFrom.value = ''
  dateTo.value = ''
  refresh()
}

function quickDateRange(days) {
  const today = new Date()
  const past = new Date(today)
  past.setDate(past.getDate() - (days - 1))
  dateFrom.value = toDateString(past)
  dateTo.value = toDateString(today)
  refresh()
}

function statusOf(h) { return statusOfHistory(h) }
function tagType(h) { return STATUS_TAG_TYPE[statusOf(h)] }
function statusClassOf(h) { return STATUS_CLASS[statusOf(h)] ?? '' }
function statusIconOf(h) { return statusIcon(statusOf(h)) }
function statusHeadlineOf(h) { return STATUS_HEADLINE[statusOf(h)] || '执行完成' }

// Parsed once per change instead of on every drawer re-render (they used to be
// plain functions, so each keystroke re-parsed and re-stringified the payload).
const paramsObj = computed(() => parseParamsJson(current.value?.parametersJson))
const paramsPretty = computed(() =>
  Object.keys(paramsObj.value).length ? JSON.stringify(paramsObj.value, null, 2) : '(无参数)'
)

// V2: snapshot pretty-print — pull from current.snapshotJson, fall back to
// the params block. Snapshot shape is { body, params, snapshotAt }.
const snapshotText = computed(() => {
  const raw = current.value?.snapshotJson
  if (!raw) return ''
  try { return JSON.stringify(JSON.parse(raw), null, 2) } catch { return raw }
})

// V3 (PR-9): snapshot date label shown on the rerun button (e.g. "2026-09-10 14:23").
const snapshotDateLabel = computed(() => {
  const raw = current.value?.snapshotJson
  if (!raw) return ''
  try {
    const o = typeof raw === 'string' ? JSON.parse(raw) : raw
    const at = o?.snapshotAt
    if (!at) return ''
    const d = new Date(at)
    if (Number.isNaN(d.getTime())) return ''
    const pad = (n) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  } catch { return '' }
})

async function openDetail(row) {
  if (!row?.id) return
  const id = row.id
  current.value = row
  currentScript.value = null  // reset; lazy-load below
  stdout.value = ''
  stderr.value = ''
  resultText.value = ''
  resultLoadedFor.value = null
  artifacts.value = []
  artifactsLoadedFor.value = null
  tab.value = 'params'
  detailOpen.value = true
  // One round-trip instead of three, and the results are dropped if the user
  // opened another row while they were in flight. Also kicks off the script
  // fetch used by the diff panel.
  const [detail, so, se] = await Promise.all([
    getHistory(id).catch(() => null),
    readStdout(id).catch(() => ''),
    readStderr(id).catch(() => '')
  ])
  if (current.value?.id !== id) return
  if (detail) current.value = detail
  stdout.value = so || ''
  stderr.value = se || ''
  // Fetch the current script schema for the diff panel (silently).
  if (current.value?.scriptId) {
    getScript(current.value.scriptId).then((s) => {
      if (current.value?.scriptId === id || current.value?.scriptId === s?.id) {
        currentScript.value = s
      }
    }).catch(() => { /* diff panel will show no diff */ })
  }
}

async function loadResult(id, force = false) {
  if (!id) return
  if (!force && resultLoadedFor.value === id) return
  resultLoading.value = true
  try {
    // readResult() resolves the parsed payload (null when the script wrote none).
    const obj = await readResult(id)
    resultText.value = obj == null ? '' : JSON.stringify(obj, null, 2)
    resultLoadedFor.value = id
  } catch {
    resultText.value = ''
  } finally {
    resultLoading.value = false
  }
}

async function loadArtifacts(id, force = false) {
  if (!id) return
  if (!force && artifactsLoadedFor.value === id) return
  artifactsLoading.value = true
  try {
    // listArtifacts() resolves the artifact array directly.
    const list = await listArtifacts(id)
    artifacts.value = Array.isArray(list) ? list : []
    artifactsLoadedFor.value = id
  } catch {
    artifacts.value = []
  } finally {
    artifactsLoading.value = false
  }
}

function downloadArtifact(row) {
  if (!current.value?.id || !row?.name) return
  window.location.href = artifactDownloadUrl(current.value.id, row.name)
}

function rerun(row) {
  if (!row) return
  // Navigate to ExecuteView with a one-shot rerun payload that ExecuteView consumes
  // from session storage to keep the URL clean.
  const payload = {
    scriptId: row.scriptId,
    tenantId: row.tenantId,
    params: parseParamsJson(row.parametersJson)
  }
  setSessionItem(KEYS.RERUN, payload)
  detailOpen.value = false
  router.push({ name: 'execute' })
}

// V2: rerun using the snapshotted body + params, even if the script has been
// edited since. The backend restores the original body on disk for the
// duration of the run, then puts it back — and blocks until it is done, so the
// new run's logs can be shown straight away.
const rerunning = ref(false)
async function rerunFromSnapshot(row) {
  if (!row?.id) return
  rerunning.value = true
  try {
    const h = await rerunExecution(row.id)
    ElMessage.success(`按快照重跑完成 (executionId=${h.id})`)
    await refresh()
    detailOpen.value = false
    await openDetail({ id: h.id })
  } catch (_) { /* interceptor toasted */ }
  finally { rerunning.value = false }
}

function copyParams() {
  copyText(Object.keys(paramsObj.value).length ? paramsPretty.value : '', '无可复制的参数')
}

function copy(text) {
  copyText(text)
}

function download(name, text) {
  downloadText(`${name}-${current.value?.id || 'history'}.log`, text)
}

onMounted(() => {
  // V3 (PR-9): restore filter + scroll position from the previous visit.
  restoreViewState()
  // Fire the (independent) lookups in parallel: the table no longer waits for
  // the tenant/script dropdown data before its first paint.
  refresh()
  Promise.all([listScripts().catch(() => []), listTenants().catch(() => [])])
    .then(([s, t]) => {
      scripts.value = s || []
      tenants.value = t || []
    })
})

// V3 (PR-9): snapshot view state every time the drawer closes so the next
// open of HistoryView can restore filters + scroll. Skip the very first
// open (when no snapshot existed yet) so cold-start users don't get stale
// state from a prior session.
watch(detailOpen, (open, prev) => {
  if (prev && !open && current.value) {
    snapshotViewState()
  }
})

// V2: lazy-load result.json / artifacts the first time the user clicks the
// tab. Re-load when the open execution changes.
watch(tab, (v) => {
  const id = current.value?.id
  if (!id) return
  if (v === 'result') loadResult(id)
  if (v === 'artifacts') loadArtifacts(id)
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

/* V3 (PR-9): diff block shown beneath params in the detail drawer. */
.sb-diff-block { margin-top: 12px; }
.sb-diff-block .sb-test-section-title { margin: 8px 0; }
/* History rows are clickable (open detail drawer). Make the affordance
   obvious so users don't miss the interaction. */
:deep(.el-table .el-table__row) { cursor: pointer; }
:deep(.el-table .el-table__row:hover > td) { background-color: var(--el-table-row-hover-bg-color); }
</style>