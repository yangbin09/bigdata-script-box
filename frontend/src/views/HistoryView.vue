<!--
  HistoryView — 执行历史（按脚本 + 按天，而不是流水列表）。

  设计依据（SIMPLIFICATION.md P1-B）：运维要回答的问题是「昨天哪个脚本失败了」，
  而不是「第 137 行是什么」。所以：
    - 首屏是「本周概览」：每个脚本一行，带按天的成功/失败分布
    - 有失败的排在前面（后端已按失败数优先排序）
    - 点某一天 → 展开该天的执行记录，可继续下钻到单次执行详情
    - 单次执行详情抽屉保留：参数 / stdout / stderr / 结果 / 产物

  「哪些执行需要被看见」优先于「如何筛选 200 条记录」，因此筛选器收进「高级筛选」。
-->
<template>
  <div>
    <div class="sb-header">
      <div>
        <h2 class="sb-page-title">历史</h2>
        <p class="sb-page-sub">
          最近 {{ days }} 天 · {{ digest.length }} 个脚本 · 共 {{ totals.total }} 次执行
          <span v-if="totals.failed" class="sb-fail-inline">（{{ totals.failed }} 次失败）</span>
        </p>
      </div>
      <div class="sb-head-actions">
        <el-radio-group v-model="days" size="small" @change="refreshDigest">
          <el-radio-button :value="1">今天</el-radio-button>
          <el-radio-button :value="7">7 天</el-radio-button>
          <el-radio-button :value="30">30 天</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" plain @click="refreshDigest" :loading="loading">刷新</el-button>
      </div>
    </div>

    <!-- 概览：哪个脚本最近出了问题 -->
    <el-table :data="digest" v-loading="loading" class="sb-card" stripe>
      <template #empty>
        <el-empty description="这个时间段还没有执行记录。" />
      </template>
      <el-table-column label="脚本" min-width="200">
        <template #default="{ row }">
          <div class="sb-name-main">{{ row.displayName || row.scriptName }}</div>
          <div class="sb-name-sub mono">{{ row.scriptName }}</div>
        </template>
      </el-table-column>
      <el-table-column label="最近一次" width="150">
        <template #default="{ row }">
          <el-tag size="small" :type="tagTypeOf(row.lastStatus)" effect="plain" disable-transitions>
            {{ labelOf(row.lastStatus) }}
          </el-tag>
          <div class="muted sb-last-time">{{ formatDateTime(row.lastRunAt) }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="`近 ${days} 天`" min-width="240">
        <template #default="{ row }">
          <div class="sb-spark">
            <span
              v-for="d in row.days.slice(0, 14)"
              :key="d.day"
              class="sb-spark-cell"
              :class="d.failed ? 'bad' : 'ok'"
              :title="`${d.day}：成功 ${d.succeeded} / 失败 ${d.failed} / 超时 ${d.timedOut}`"
              @click.stop="openRuns(row, d.day)"
            >
              <span class="sb-spark-bar" :style="{ height: barHeight(d) }" />
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="成功/失败" width="120" align="right">
        <template #default="{ row }">
          <span class="mono">{{ row.succeeded }}/{{ row.failed }}</span>
          <div v-if="row.failed" class="sb-rate danger">{{ Math.round(row.failureRate * 100) }}% 失败</div>
          <div v-else class="sb-rate ok">全部成功</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" align="right" fixed="right">
        <template #default="{ row }">
          <el-button size="small" :icon="RefreshRight" type="primary" plain @click.stop="rerunLast(row)">
            跑上次
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 高级筛选（默认收起：回答"谁失败了"不需要它） -->
    <el-collapse class="sb-advanced">
      <el-collapse-item name="filters">
        <template #title>
          <span class="sb-advanced-title">高级筛选 / 全部记录</span>
          <span class="muted sb-advanced-hint">按状态、脚本、租户、关键字、日期查原始记录</span>
        </template>
        <div class="sb-filter">
          <div class="sb-filter-row">
            <el-select v-model="filters.scriptId" placeholder="按脚本" clearable filterable style="width: 200px">
              <el-option v-for="s in scripts" :key="s.id" :label="s.displayName || s.name" :value="s.id" />
            </el-select>
            <el-select v-model="filters.tenantId" placeholder="按租户" clearable filterable style="width: 180px">
              <el-option v-for="t in tenants" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
            <el-select v-model="filters.status" placeholder="按状态" clearable style="width: 140px">
              <el-option label="成功" value="success" />
              <el-option label="失败" value="failed" />
              <el-option label="超时" value="timeout" />
              <el-option label="已取消" value="cancelled" />
            </el-select>
            <el-input v-model="filters.keyword" placeholder="脚本/租户关键字" clearable style="width: 200px" />
            <el-date-picker v-model="filters.dateFrom" type="date" value-format="YYYY-MM-DD" placeholder="开始" style="width: 140px" />
            <span class="muted">~</span>
            <el-date-picker v-model="filters.dateTo" type="date" value-format="YYYY-MM-DD" placeholder="结束" style="width: 140px" />
            <el-button type="primary" plain @click="runQuery">查询</el-button>
            <el-button @click="resetFilters">清空</el-button>
          </div>
        </div>

        <el-table :data="rows" v-loading="rowsLoading" class="sb-card" stripe size="small" @row-click="openDetail">
          <el-table-column label="#" width="150" prop="id" />
          <el-table-column label="脚本" min-width="180">
            <template #default="{ row }">{{ row.scriptName }}</template>
          </el-table-column>
          <el-table-column label="租户" width="130">
            <template #default="{ row }"><span class="muted">{{ row.tenantName || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="tagTypeOf(statusOf(row))" effect="plain" disable-transitions>
                {{ labelOf(statusOf(row)) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="退出码" width="80" align="right">
            <template #default="{ row }"><span class="mono">{{ row.exitCode ?? '-' }}</span></template>
          </el-table-column>
          <el-table-column label="耗时" width="100" align="right">
            <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
          </el-table-column>
          <el-table-column label="开始时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.startTime) }}</template>
          </el-table-column>
        </el-table>
      </el-collapse-item>
    </el-collapse>

    <!-- 某天的执行记录 -->
    <el-dialog v-model="runsOpen" :title="runsTitle" width="760px">
      <el-table :data="runsRows" v-loading="runsLoading" stripe size="small" @row-click="openDetail">
        <el-table-column label="#" width="150" prop="id" />
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="tagTypeOf(statusOf(row))" effect="plain" disable-transitions>
              {{ labelOf(statusOf(row)) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="退出码" width="80" align="right">
          <template #default="{ row }"><span class="mono">{{ row.exitCode ?? '-' }}</span></template>
        </el-table-column>
        <el-table-column label="耗时" width="100" align="right">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="开始时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.startTime) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 单次执行详情 -->
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

        <el-tabs v-model="tab" @tab-change="onTabChange">
          <el-tab-pane label="参数" name="params">
            <div class="sb-log-toolbar">
              <el-button size="small" :icon="DocumentCopy" @click="copyParams">复制参数</el-button>
            </div>
            <pre class="sb-log">{{ paramsPretty }}</pre>
          </el-tab-pane>
          <el-tab-pane label="stdout" name="stdout">
            <div class="sb-log-toolbar">
              <el-button size="small" :icon="DocumentCopy" :disabled="!!stdoutError" @click="copy(stdout)">复制</el-button>
              <el-button size="small" :icon="Download" :disabled="!!stdoutError" @click="download('stdout', stdout)">下载</el-button>
              <el-button v-if="stdoutError" size="small" :icon="Refresh" @click="refreshLogs(current?.id)">重新读取</el-button>
            </div>
            <pre class="sb-log sb-log-failed" v-if="stdoutError">读取 stdout 失败：{{ stdoutError }}</pre>
            <pre class="sb-log" v-else-if="stdout">{{ stdout }}</pre>
            <div v-else class="sb-log sb-log-empty">无 stdout 输出</div>
          </el-tab-pane>
          <el-tab-pane label="stderr" name="stderr">
            <div class="sb-log-toolbar">
              <el-button size="small" :icon="DocumentCopy" :disabled="!!stderrError" @click="copy(stderr)">复制</el-button>
              <el-button size="small" :icon="Download" :disabled="!!stderrError" @click="download('stderr', stderr)">下载</el-button>
              <el-button v-if="stderrError" size="small" :icon="Refresh" @click="refreshLogs(current?.id)">重新读取</el-button>
            </div>
            <pre class="sb-log sb-log-failed" v-if="stderrError">读取 stderr 失败：{{ stderrError }}</pre>
            <pre class="sb-log" v-else-if="stderr">{{ stderr }}</pre>
            <div v-else class="sb-log sb-log-empty">无 stderr 输出</div>
          </el-tab-pane>
          <el-tab-pane label="结果" name="result">
            <div class="sb-log-toolbar">
              <el-button size="small" :icon="Refresh" :loading="resultLoading" @click="loadResult(current?.id, true)">刷新</el-button>
              <el-button size="small" :icon="DocumentCopy" :disabled="!resultText" @click="copy(resultText)">复制</el-button>
            </div>
            <pre class="sb-log" v-if="resultText">{{ resultText }}</pre>
            <div v-else class="sb-log sb-log-empty">
              {{ resultLoading ? '加载中…' : '未生成 result.json（脚本未写入结构化结果）' }}
            </div>
          </el-tab-pane>
          <el-tab-pane :label="`产物 (${artifacts.length})`" name="artifacts">
            <div class="sb-log-toolbar">
              <el-button size="small" :icon="Refresh" :loading="artifactsLoading" @click="loadArtifacts(current?.id, true)">刷新</el-button>
            </div>
            <el-table v-if="artifacts.length" :data="artifacts" class="sb-card" stripe size="small">
              <el-table-column label="文件名" min-width="220">
                <template #default="{ row }"><span class="mono">{{ row.name }}</span></template>
              </el-table-column>
              <el-table-column label="大小" width="110" align="right">
                <template #default="{ row }"><span class="mono">{{ formatBytes(row.sizeBytes) }}</span></template>
              </el-table-column>
              <el-table-column label="操作" width="110" align="right">
                <template #default="{ row }">
                  <el-button size="small" type="primary" plain :icon="Download" @click="downloadArtifact(row)">下载</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div v-else class="sb-log sb-log-empty">
              {{ artifactsLoading ? '加载中…' : '无产物文件（脚本未写入 $ARTIFACT_DIR）' }}
            </div>
          </el-tab-pane>
        </el-tabs>
      </template>

      <template #footer>
        <div class="sb-exec-footer">
          <el-button @click="detailOpen = false">关闭</el-button>
          <div class="right">
            <el-button :icon="DocumentCopy" @click="copyParams">复制参数</el-button>
            <el-button :icon="RefreshRight" type="primary" @click="rerun(current)">使用当前脚本重跑</el-button>
          </div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Refresh, RefreshRight, Close, DocumentCopy, Download } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listHistory, getHistory, historyDigest, lastPlan } from '../api/history'
import { readStdout, readStderr, readResult, listArtifacts, artifactDownloadUrl } from '../api/executions'
import { listScripts } from '../api/scripts'
import { listTenants } from '../api/tenants'
import { formatDateTime, formatDuration, parseParamsJson, formatBytes } from '../utils/format'
import {
  STATUS_CLASS, STATUS_HEADLINE, statusOfHistory, labelOf, tagTypeOf
} from '../utils/labels'
import { statusIcon } from '../utils/status'
import { copyText, downloadText } from '../utils/clipboard'
import { setSessionItem, KEYS } from '../utils/storage'

const router = useRouter()
const route = useRoute()

// ---- 概览 ----
const days = ref(7)
const digest = ref([])
const loading = ref(false)

const totals = computed(() => {
  let total = 0, failed = 0
  for (const d of digest.value) {
    total += d.total
    failed += d.failed
  }
  return { total, failed }
})

/** 迷你柱状图高度：按当天总次数归一，0 次留 2px 占位。 */
function barHeight(d) {
  const max = Math.max(1, ...digest.value.flatMap((x) => (x.days || []).map((y) => y.total)))
  const h = d.total === 0 ? 2 : Math.max(4, Math.round((d.total / max) * 22))
  return `${h}px`
}

async function refreshDigest() {
  loading.value = true
  try {
    digest.value = (await historyDigest(days.value)) || []
  } finally {
    loading.value = false
  }
}

// ---- 高级筛选 / 原始记录 ----
const filters = reactive({
  scriptId: null, tenantId: null, status: '', keyword: '', dateFrom: '', dateTo: ''
})
const rows = ref([])
const rowsLoading = ref(false)
const scripts = ref([])
const tenants = ref([])

async function runQuery() {
  rowsLoading.value = true
  try {
    const params = { limit: 200 }
    if (filters.scriptId) params.scriptId = filters.scriptId
    if (filters.tenantId) params.tenantId = filters.tenantId
    if (filters.status) params.status = filters.status
    if (filters.keyword?.trim()) params.keyword = filters.keyword.trim()
    if (filters.dateFrom) params.from = filters.dateFrom
    if (filters.dateTo) params.to = filters.dateTo
    rows.value = (await listHistory(params)) || []
  } catch (_) { /* interceptor surfaced */ } finally {
    rowsLoading.value = false
  }
}

function resetFilters() {
  Object.assign(filters, {
    scriptId: null, tenantId: null, status: '', keyword: '', dateFrom: '', dateTo: ''
  })
  runQuery()
}

// ---- 某天的执行记录 ----
const runsOpen = ref(false)
const runsLoading = ref(false)
const runsRows = ref([])
const runsTitle = ref('')

async function openRuns(row, day) {
  runsTitle.value = `${row.displayName || row.scriptName} · ${day}`
  runsOpen.value = true
  runsLoading.value = true
  try {
    runsRows.value = (await listHistory({
      limit: 200, scriptId: row.scriptId, from: day, to: day
    })) || []
  } catch (_) {
    runsRows.value = []
  } finally {
    runsLoading.value = false
  }
}

// ---- 单次详情 ----
const detailOpen = ref(false)
const current = ref(null)
const stdout = ref('')
const stderr = ref('')
const stdoutError = ref('')
const stderrError = ref('')
const tab = ref('params')
const resultText = ref('')
const resultLoading = ref(false)
const artifacts = ref([])
const artifactsLoading = ref(false)

function statusOf(h) { return statusOfHistory(h) }
function statusClassOf(h) { return STATUS_CLASS[statusOf(h)] ?? '' }
function statusIconOf(h) { return statusIcon(statusOf(h)) }
function statusHeadlineOf(h) { return STATUS_HEADLINE[statusOf(h)] || '执行完成' }

const paramsPretty = computed(() => {
  const p = parseParamsJson(current.value?.parametersJson)
  return Object.keys(p).length ? JSON.stringify(p, null, 2) : '(无参数)'
})

async function openDetail(row) {
  const id = row?.id
  if (!id) return
  current.value = row
  stdout.value = ''
  stderr.value = ''
  stdoutError.value = ''
  stderrError.value = ''
  resultText.value = ''
  artifacts.value = []
  tab.value = 'params'
  detailOpen.value = true
  const [detail] = await Promise.all([
    getHistory(id).catch(() => null),
    refreshLogs(id)
  ])
  if (current.value?.id !== id) return
  if (detail) current.value = detail
}

/** 懒加载「结果 / 产物」两个 Tab，首次点击才拉。 */
function onTabChange(name) {
  const id = current.value?.id
  if (!id) return
  if (name === 'result') loadResult(id)
  if (name === 'artifacts') loadArtifacts(id)
}

/** 读日志失败要把原因留下，不能吞成空字符串冒充「无输出」。 */
async function refreshLogs(id) {
  const target = id || current.value?.id
  if (!target) return
  try {
    const text = await readStdout(target)
    if (current.value?.id !== target) return
    stdout.value = typeof text === 'string' ? text : ''
    stdoutError.value = ''
  } catch (e) {
    if (current.value?.id !== target) return
    stdout.value = ''
    stdoutError.value = e?.message || '读取失败'
  }
  try {
    const text = await readStderr(target)
    if (current.value?.id !== target) return
    stderr.value = typeof text === 'string' ? text : ''
    stderrError.value = ''
  } catch (e) {
    if (current.value?.id !== target) return
    stderr.value = ''
    stderrError.value = e?.message || '读取失败'
  }
}

async function loadResult(id) {
  if (!id) return
  resultLoading.value = true
  try {
    const obj = await readResult(id)
    resultText.value = obj == null ? '' : JSON.stringify(obj, null, 2)
  } catch {
    resultText.value = ''
  } finally {
    resultLoading.value = false
  }
}

async function loadArtifacts(id) {
  if (!id) return
  artifactsLoading.value = true
  try {
    const list = await listArtifacts(id)
    artifacts.value = Array.isArray(list) ? list : []
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

/** 「跑上次」：把该脚本上次成功的参数交给工作台直接执行。 */
async function rerunLast(row) {
  try {
    const plan = await lastPlan(row.scriptId, null)
    if (!plan?.executionId) {
      ElMessage.info('这个脚本还没有成功记录')
      return
    }
    setSessionItem(KEYS.RERUN, {
      scriptId: row.scriptId,
      tenantId: plan.tenantId,
      params: plan.parameters,
      autoRun: true
    })
    router.push({ name: 'workbench' })
  } catch (e) {
    ElMessage.warning(`读取上次参数失败：${e?.message || '未知错误'}`)
  }
}

function rerun(row) {
  if (!row?.scriptId) return
  setSessionItem(KEYS.RERUN, {
    scriptId: row.scriptId,
    tenantId: row.tenantId,
    params: parseParamsJson(row.parametersJson)
  })
  detailOpen.value = false
  router.push({ name: 'workbench' })
}

function copyParams() {
  const p = parseParamsJson(current.value?.parametersJson)
  copyText(Object.keys(p).length ? paramsPretty.value : '', '无可复制的参数')
}
function copy(text) { copyText(text) }
function download(name, text) { downloadText(`${name}-${current.value?.id || 'history'}.log`, text) }

onMounted(async () => {
  refreshDigest()
  runQuery()
  Promise.all([listScripts().catch(() => []), listTenants().catch(() => [])])
    .then(([s, t]) => { scripts.value = s || []; tenants.value = t || [] })
  // 支持 /history?id=... 直达某次执行（任务中心 / 全局查找会这样跳）
  const id = route.query.id
  if (id) await openDetail({ id: Number(id) })
})
</script>

<style scoped>
.sb-head-actions { display: flex; align-items: center; gap: 8px; }
.sb-fail-inline { color: var(--el-color-danger); }
.sb-name-main { font-weight: 600; font-size: 13.5px; }
.sb-name-sub { font-size: 11.5px; color: var(--sb-text-3); }
.sb-last-time { font-size: 11.5px; margin-top: 2px; }

/* 按天迷你柱状图：点某一格看那天的记录 */
.sb-spark { display: flex; align-items: flex-end; gap: 3px; height: 26px; }
.sb-spark-cell {
  display: flex; align-items: flex-end;
  width: 10px; height: 24px; cursor: pointer;
}
.sb-spark-bar { width: 100%; border-radius: 2px 2px 0 0; background: #86efac; }
.sb-spark-cell.bad .sb-spark-bar { background: #fca5a5; }

.sb-rate { font-size: 11.5px; }
.sb-rate.danger { color: var(--el-color-danger); }
.sb-rate.ok { color: var(--el-color-success); }

.sb-advanced { margin-top: 16px; }
.sb-advanced-title { font-size: 13px; }
.sb-advanced-hint { margin-left: 8px; font-size: 12px; }
.sb-filter { padding: 4px 0 12px; }
.sb-filter-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }

.sb-log-failed {
  color: var(--el-color-danger);
  background: #fef2f2 !important;
  border-color: #fecaca !important;
  font-style: normal;
}
:deep(.el-table .el-table__row) { cursor: pointer; }
</style>
