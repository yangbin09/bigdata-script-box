<!--
  ExecuteView — the front page. Reorganized for fast, high-frequency use.

  Top: search box + refresh.
  Section: 最近使用 (only if there is history; shows up to 6 distinct scripts).
  Section: 常用脚本 (favorite=true; only if any).
  Section: one block per category. Disabled scripts are hidden entirely.

  Drawer: opens directly into the execution flow for a script.
    - Header: script display name + (category · timeout N 秒).
    - Sub-row: tenant select (pre-populated from defaultTenantId > lastTenant > single-tenant-auto).
    - ParamForm: seeded with last-used params for this script (falls back to defaults).
    - Footer: [恢复默认参数] [取消] [执行脚本].
    - On run: button becomes "执行中 … N 秒", live elapsed-time ticker.
    - On finish: drawer stays open; form is replaced by ExecutionResultPanel with
      再次执行 / 修改参数 / 查看历史 / 关闭 buttons.
-->
<template>
  <div>
    <div class="sb-header">
      <div>
        <h2 class="sb-page-title">执行中心</h2>
        <p class="sb-page-sub">选择脚本，填写必要参数后执行。最近用过的脚本会出现在最顶部。</p>
      </div>
      <el-button :icon="Refresh" plain @click="refreshAll" :loading="loading">刷新</el-button>
    </div>

    <div class="sb-toolbar">
      <el-input
        v-model="search"
        placeholder="搜索脚本名称 / 描述 / 分类"
        clearable
        :prefix-icon="Search"
        class="sb-search"
      />
    </div>

    <el-empty
      v-if="!loading && !filteredScripts.length"
      description="暂无可执行脚本。先去「脚本管理」创建或调整脚本的启用状态。"
    />

    <!-- 最近使用 -->
    <section v-if="recentItems.length" class="sb-block">
      <h3 class="sb-section-title">最近使用</h3>
      <div class="sb-card-grid">
        <div
          v-for="r in recentItems"
          :key="r.id"
          class="sb-script-card sb-script-card-recent"
          @click="openDrawerById(r.id)"
        >
          <div class="sb-recent-head">
            <span class="sb-recent-name">{{ recentDisplayName(r) }}</span>
            <el-tag
              v-if="r.lastSuccess === true"
              size="small"
              type="success"
              disable-transitions
              effect="plain"
            >成功</el-tag>
            <el-tag
              v-else-if="r.lastSuccess === false"
              size="small"
              type="danger"
              disable-transitions
              effect="plain"
            >失败</el-tag>
          </div>
          <div class="sb-recent-meta">
            <span>{{ r.lastTenantName || '—' }}</span>
            <span class="dot">·</span>
            <span>{{ formatDateTime(r.lastStartTime) }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 常用脚本 (收藏) -->
    <section v-if="favoriteScripts.length" class="sb-block">
      <h3 class="sb-section-title">
        常用脚本
        <span class="count">{{ favoriteScripts.length }}</span>
      </h3>
      <div class="sb-card-grid">
        <ScriptCard
          v-for="s in favoriteScripts"
          :key="`fav-${s.id}`"
          :script="s"
          @click="openDrawer(s)"
          @toggle-favorite="(v) => toggleFavorite(s, v)"
        />
      </div>
    </section>

    <!-- 按分类 -->
    <section
      v-for="group in groupedVisible"
      :key="group.category"
      class="sb-block"
    >
      <h3
        class="sb-section-title sb-group-title"
        @click="collapsed[group.category] = !collapsed[group.category]"
      >
        <el-icon :size="14">
          <component :is="collapsed[group.category] ? ArrowRight : ArrowDown" />
        </el-icon>
        {{ group.category }}
        <span class="count">{{ group.scripts.length }}</span>
      </h3>
      <div v-show="!collapsed[group.category]" class="sb-card-grid">
        <ScriptCard
          v-for="s in group.scripts"
          :key="s.id"
          :script="s"
          @click="openDrawer(s)"
          @toggle-favorite="(v) => toggleFavorite(s, v)"
        />
      </div>
    </section>

    <!-- 执行 Drawer -->
    <el-drawer
      v-model="drawerOpen"
      :direction="'rtl'"
      :size="drawerSize"
      :destroy-on-close="false"
      :show-close="false"
      :wrapper-closable="false"
      class="sb-exec-drawer"
    >
      <template #header>
        <div class="sb-drawer-header">
          <div>
            <div class="sb-drawer-title">
              {{ activeScript?.displayName || activeScript?.name }}
              <el-tag
                v-if="activeScript"
                size="small" disable-transitions effect="plain"
                :type="RISK_LEVEL_TAG_TYPE[normalizeRiskLevel(activeScript.riskLevel)]"
                class="sb-title-tag"
              >{{ RISK_LEVEL_LABEL[normalizeRiskLevel(activeScript.riskLevel)] }}</el-tag>
            </div>
            <div v-if="activeScript" class="sb-exec-sub">
              <span>{{ activeScript.category || '默认' }}</span>
              <span class="dot">·</span>
              <span>超时 {{ activeScript.timeoutSeconds || 600 }} 秒</span>
            </div>
          </div>
          <el-button text :icon="Close" @click="closeDrawer" />
        </div>
      </template>

      <template v-if="activeScript">
        <!-- 表单视图 -->
        <div v-if="!resultHistory">
          <div class="sb-form-section">
            <div class="sb-form-section-label">租户</div>
            <el-select
              v-model="tenantId"
              :disabled="running"
              style="width: 100%"
            >
              <el-option
                v-for="t in enabledTenants"
                :key="t.id"
                :label="`${t.name}（${t.principal || '-'}）`"
                :value="t.id"
              />
            </el-select>
          </div>

          <div class="sb-form-section">
            <div class="sb-form-section-label">
              参数方案 <span class="muted">(可选，覆盖默认参数)</span>
            </div>
            <el-select
              v-model="presetId"
              :disabled="running"
              clearable
              placeholder="不指定 (使用默认参数)"
              style="width: 100%"
              @change="applyPresetToForm"
            >
              <el-option
                v-for="p in presets"
                :key="p.id"
                :label="p.name"
                :value="p.id"
              />
            </el-select>
          </div>

          <div class="sb-form-section">
            <ParamForm
              ref="formRef"
              :params="activeParams"
              :initial-values="lastParamsForScript"
              v-model="formValues"
            />
          </div>

          <!-- 文件参数 -->
          <div
            v-if="fileParamNames.length"
            class="sb-form-section"
          >
            <div class="sb-form-section-label">文件参数</div>
            <div v-for="p in fileParamNames" :key="p" class="sb-file-row">
              <div class="sb-file-row-label">{{ p }}</div>
              <el-upload
                :auto-upload="true"
                :show-file-list="false"
                :http-request="(opts) => uploadFileFor(opts.file, p)"
                :before-upload="(f) => beforeUploadFile(f, p)"
                accept="*"
              >
                <el-button size="small" :icon="UploadFilled">选择文件</el-button>
              </el-upload>
              <span v-if="fileInputs[p]" class="sb-file-name">
                {{ fileInputs[p].originalName }} ({{ formatBytes(fileInputs[p].bytes) }})
              </span>
              <el-button v-else size="small" link type="info" disabled>未上传</el-button>
            </div>
          </div>

          <!-- 批量执行 -->
          <div class="sb-form-section">
            <div class="sb-form-section-label">
              <el-checkbox v-model="batchMode" :disabled="running">批量执行</el-checkbox>
              <span class="muted" style="margin-left: 8px">每行 JSON：{"参数": "值"}</span>
            </div>
            <el-input
              v-if="batchMode"
              v-model="batchRowsText"
              type="textarea"
              :rows="5"
              placeholder='例如：&#10;{"database": "dev"}&#10;{"database": "prod"}'
              :disabled="running"
            />
          </div>
        </div>

        <!-- 结果视图 (执行完成后) -->
        <ExecutionResultPanel
          v-else
          :history="resultHistory"
          :log-stdout="resultStdout"
          :log-stderr="resultStderr"
          @rerun="rerunFromResult"
          @edit="backToForm"
          @view-history="goHistory"
        />
      </template>

      <template #footer>
        <div v-if="activeScript" class="sb-exec-footer">
          <!-- 表单页 footer -->
          <template v-if="!resultHistory">
            <el-button :disabled="running" @click="resetDefaults">恢复默认参数</el-button>
            <div class="right">
              <el-button @click="openPreview" :disabled="running" :loading="previewing">预览</el-button>
              <el-button @click="closeDrawer" :disabled="running">取消</el-button>
              <el-button
                type="primary"
                :icon="running ? Loading : VideoPlay"
                :loading="running"
                :disabled="!tenantId"
                @click="runScript"
              >{{ running ? `执行中… ${elapsed}s` : (batchMode ? '批量执行' : '执行脚本') }}</el-button>
            </div>
          </template>
          <!-- 结果页 footer -->
          <template v-else>
            <el-button @click="closeDrawer">关闭</el-button>
            <div class="right">
              <el-button :icon="EditPen" @click="backToForm">修改参数</el-button>
              <el-button :icon="RefreshRight" type="primary" @click="rerunFromResult">再次执行</el-button>
            </div>
          </template>
        </div>
      </template>
    </el-drawer>

    <!-- 预览 Drawer -->
    <el-drawer
      v-model="previewOpen"
      title="执行预览 (Dry Run)"
      direction="rtl"
      size="560px"
      :show-close="false"
      class="sb-exec-drawer"
    >
      <template #header>
        <div class="sb-drawer-header">
          <div class="sb-drawer-title">执行预览 — {{ activeScript?.displayName || activeScript?.name }}</div>
          <el-button text :icon="Close" @click="previewOpen = false" />
        </div>
      </template>
      <template v-if="previewData">
        <h4 class="sb-test-section-title">命令</h4>
        <pre class="sb-log sb-cmd">{{ previewData.command.join(' ') }}</pre>
        <h4 class="sb-test-section-title">参数 (--key value)</h4>
        <pre class="sb-log">{{ JSON.stringify(previewData.params, null, 2) }}</pre>
        <h4 class="sb-test-section-title">环境变量 (敏感已脱敏)</h4>
        <pre class="sb-log">{{ JSON.stringify(previewData.globalVariables, null, 2) }}</pre>
        <div class="sb-preview-meta">
          <div><span class="muted">租户：</span>{{ previewData.tenantName }}</div>
          <div><span class="muted">principal：</span>{{ previewData.principal || '—' }}</div>
          <div><span class="muted">超时：</span>{{ previewData.timeoutSeconds }} 秒</div>
          <div><span class="muted">kinit 包装：</span>{{ previewData.kinitWrapped ? '是' : '否' }}</div>
          <div><span class="muted">keytab：</span>{{ previewData.keytabPath || '未配置' }}</div>
        </div>
      </template>
      <template v-else>
        <div v-loading="previewing" style="height: 80px" />
      </template>
    </el-drawer>

    <!-- 批量执行结果 Drawer -->
    <el-drawer
      v-model="batchResultOpen"
      :title="`批量执行结果 — ${batchResult?.batchId || ''}`"
      direction="rtl"
      size="640px"
      :show-close="false"
      class="sb-exec-drawer"
    >
      <template v-if="batchResult">
        <div class="sb-status-card ok">
          <div class="sb-status-headline">成功 {{ batchResult.succeeded }} / 失败 {{ batchResult.failed }} / 总 {{ batchResult.total }}</div>
        </div>
        <el-table :data="batchRowsForTable" class="sb-card" stripe>
          <el-table-column label="#" width="60">
            <template #default="{ $index }">#{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column label="脚本" prop="scriptName" min-width="200" />
          <el-table-column label="租户" prop="tenantName" min-width="120" />
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.success ? 'success' : 'danger'" disable-transitions effect="plain">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="100" align="right">
            <template #default="{ row }">{{ row.durationMs || 0 }} ms</template>
          </el-table-column>
          <el-table-column label="history" width="100" align="right">
            <template #default="{ row }">
              <span class="mono">{{ row.id }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Refresh, Search, VideoPlay, Loading, Close, ArrowRight, ArrowDown,
  EditPen, RefreshRight, UploadFilled
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listScripts, getScript, setScriptFavorite } from '../api/scripts'
import { listTenants } from '../api/tenants'
import { execute, readStdout, readStderr } from '../api/executions'
import { recentScripts } from '../api/history'
import { listPresets, dryRun, uploadFile, runBatch, getBatch } from '../api/extras'
import ScriptCard from '../components/ScriptCard.vue'
import ParamForm from '../components/ParamForm.vue'
import ExecutionResultPanel from '../components/ExecutionResultPanel.vue'
import { formatDateTime } from '../utils/format'
import { getItem, setItem, removeItem } from '../utils/storage'
import { RISK_LEVEL, RISK_LEVEL_LABEL, RISK_LEVEL_TAG_TYPE, normalizeRiskLevel, RISK_CONFIRM_TOKEN } from '../utils/labels'

const router = useRouter()
const scripts = ref([])
const tenants = ref([])
const recentList = ref([])
const loading = ref(false)

const search = ref('')
const collapsed = reactive({})

// Drawer state
const drawerOpen = ref(false)
const drawerSize = ref('520px')
const activeScript = ref(null)
const activeParams = ref([])
const formValues = ref({})
const tenantId = ref(null)
const formRef = ref(null)
const running = ref(false)
const elapsed = ref(0)
let elapsedTimer = null

// V1.5: preset, file inputs, dry-run preview, batch
const presets = ref([])
const presetId = ref(null)
const fileInputs = reactive({})          // paramName -> {serverPath, originalName, bytes}
const previewOpen = ref(false)
const previewData = ref(null)
const previewing = ref(false)
const batchMode = ref(false)
const batchRowsText = ref('')
const batchResult = ref(null)
const batchResultOpen = ref(false)
const batchRowsForTable = ref([])

// Result state
const resultHistory = ref(null)
const resultStdout = ref('')
const resultStderr = ref('')

const LAST_TENANT_KEY = 'sb.lastTenantId'
const LAST_PARAMS_KEY = 'sb.lastParams' // map: { [scriptId]: { [paramName]: value } }

const enabledTenants = computed(() =>
  (tenants.value || []).filter((t) => t.enabled !== false)
)

const filteredScripts = computed(() => {
  const term = search.value.trim().toLowerCase()
  return (scripts.value || []).filter((s) => {
    if (s.enabled === false) return false
    if (!term) return true
    return (s.displayName || s.name || '').toLowerCase().includes(term)
      || (s.description || '').toLowerCase().includes(term)
      || (s.category || '').toLowerCase().includes(term)
      || (s.name || '').toLowerCase().includes(term)
  })
})

const favoriteScripts = computed(() =>
  filteredScripts.value.filter((s) => s.favorite)
)

const groupedVisible = computed(() => {
  const favSet = new Set(favoriteScripts.value.map((s) => s.id))
  const map = new Map()
  for (const s of filteredScripts.value) {
    if (favSet.has(s.id)) continue
    const k = s.category || '默认'
    if (!map.has(k)) map.set(k, [])
    map.get(k).push(s)
  }
  // Sort: non-Mock categories first, Mock last
  const list = [...map.entries()].map(([category, list]) => ({ category, scripts: list }))
  list.sort((a, b) => {
    const am = a.category === 'Mock' ? 1 : 0
    const bm = b.category === 'Mock' ? 1 : 0
    return am - bm
  })
  return list
})

const recentItems = computed(() => {
  const byId = new Map(scripts.value.map((s) => [s.id, s]))
  return (recentList.value || []).filter((r) => byId.has(r.id))
})

function recentDisplayName(r) {
  const s = scripts.value.find((x) => x.id === r.id)
  return s ? (s.displayName || s.name) : (r.scriptName || '')
}

function pickInitialTenant(script) {
  // 1) explicit default on the script
  if (script?.defaultTenantId && enabledTenants.value.some((t) => t.id === script.defaultTenantId))
    return script.defaultTenantId
  // 2) last tenant from localStorage
  const last = getItem(LAST_TENANT_KEY)
  if (last && enabledTenants.value.some((t) => t.id === last)) return last
  // 3) only one tenant -> auto-pick
  if (enabledTenants.value.length === 1) return enabledTenants.value[0].id
  return null
}

function lastParamsForScript() {
  if (!activeScript.value) return {}
  const all = getItem(LAST_PARAMS_KEY, {}) || {}
  return all[activeScript.value.id] || {}
}

async function refreshAll() {
  loading.value = true
  try {
    const [s, t, r] = await Promise.all([
      listScripts(),
      listTenants(),
      recentScripts(6).catch(() => [])
    ])
    scripts.value = s || []
    tenants.value = t || []
    recentList.value = r || []
  } finally {
    loading.value = false
  }
}

async function openDrawerById(id) {
  const s = scripts.value.find((x) => x.id === id)
  if (!s) return ElMessage.warning('脚本已被删除')
  await openDrawer(s)
}

// Honor a one-shot rerun handoff placed by HistoryView.
async function consumeRerun() {
  try {
    const raw = sessionStorage.getItem('sb.rerun')
    if (!raw) return
    sessionStorage.removeItem('sb.rerun')
    const payload = JSON.parse(raw)
    if (!payload?.scriptId) return
    const s = scripts.value.find((x) => x.id === payload.scriptId)
    if (!s) {
      ElMessage.warning('原脚本已不存在，无法重跑')
      return
    }
    if (s.enabled === false) {
      ElMessage.warning('脚本已禁用，无法重跑')
      return
    }
    await openDrawer(s)
    // After form is open, apply the rerun overrides
    if (payload.tenantId && enabledTenants.value.some((t) => t.id === payload.tenantId)) {
      tenantId.value = payload.tenantId
    }
    if (payload.params && typeof payload.params === 'object') {
      // Merge over formValues so unrecognized params don't show up
      const next = { ...formValues.value }
      for (const [k, v] of Object.entries(payload.params)) next[k] = v == null ? '' : String(v)
      formValues.value = next
    }
  } catch (_) { /* ignore malformed payload */ }
}

async function openDrawer(s) {
  if (s.enabled === false) {
    ElMessage.warning('脚本已禁用，无法执行')
    return
  }
  activeScript.value = s
  resultHistory.value = null
  resultStdout.value = ''
  resultStderr.value = ''
  elapsed.value = 0
  presetId.value = null
  batchMode.value = false
  batchRowsText.value = ''
  batchResult.value = null
  for (const k of Object.keys(fileInputs)) delete fileInputs[k]
  try {
    const [detail, ps] = await Promise.all([
      getScript(s.id),
      listPresets(s.id).catch(() => [])
    ])
    activeParams.value = detail?.params || []
    presets.value = ps || []
  } catch {
    activeParams.value = []
    presets.value = []
  }
  formValues.value = {}
  tenantId.value = pickInitialTenant(s)
  drawerOpen.value = true
}

const fileParamNames = computed(() =>
  (activeParams.value || []).filter((p) => p.type === 'file').map((p) => p.name)
)

async function applyPresetToForm(pid) {
  if (!pid) return
  const p = presets.value.find((x) => x.id === pid)
  if (!p) return
  let parsed = {}
  try { parsed = p.paramsJson ? JSON.parse(p.paramsJson) : {} } catch {}
  const next = { ...formValues.value }
  for (const [k, v] of Object.entries(parsed)) next[k] = v == null ? '' : String(v)
  formValues.value = next
}

function beforeUploadFile(file) {
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('文件超过 10MB 上限')
    return false
  }
  return true
}

async function uploadFileFor(file, paramName) {
  try {
    const res = await uploadFile(file)
    fileInputs[paramName] = {
      serverPath: res.path,
      originalName: file.name,
      bytes: file.size
    }
    ElMessage.success(`${paramName} 上传完成`)
  } catch (e) {
    // interceptor already toasted
  }
}

function formatBytes(n) {
  if (!n) return '0 B'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

async function openPreview() {
  previewing.value = true
  previewData.value = null
  previewOpen.value = true
  try {
    const params = {}
    for (const [k, v] of Object.entries(formValues.value)) {
      if (v === '' || v == null) continue
      params[k] = String(v)
    }
    previewData.value = await dryRun({
      scriptId: activeScript.value.id,
      tenantId: tenantId.value,
      params,
      presetId: presetId.value
    })
  } catch {
    previewOpen.value = false
  } finally {
    previewing.value = false
  }
}

function collectParams() {
  const out = {}
  for (const [k, v] of Object.entries(formValues.value)) {
    if (v === '' || v == null) continue
    if (fileInputs[k]) { out[k] = fileInputs[k].serverPath; continue }
    out[k] = String(v)
  }
  return out
}

function parseBatchRows() {
  const out = []
  const text = (batchRowsText.value || '').trim()
  if (!text) return out
  for (const line of text.split(/\r?\n/)) {
    const t = line.trim()
    if (!t) continue
    try {
      const o = JSON.parse(t)
      if (o && typeof o === 'object') out.push(o)
    } catch {
      ElMessage.warning(`无法解析行：${t.slice(0, 60)}`)
    }
  }
  return out
}

function closeDrawer() {
  if (running.value) {
    ElMessage.warning('脚本正在执行，无法关闭')
    return
  }
  drawerOpen.value = false
}

function resetDefaults() {
  formRef.value?.resetToDefaults?.()
}

function backToForm() {
  resultHistory.value = null
  resultStdout.value = ''
  resultStderr.value = ''
}

function goHistory() {
  closeDrawer()
  router.push('/history')
}

async function runScript() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  // V2: DANGEROUS scripts require the user to type CONFIRM. Dry-run / batch /
  // scripted paths do not go through this gate (they call /api/executions/preview
  // or send bypassDangerousCheck via the snapshot re-run flow).
  const riskLevel = normalizeRiskLevel(activeScript.value?.riskLevel)
  let confirmToken = null
  if (riskLevel === RISK_LEVEL.DANGEROUS) {
    try {
      const result = await ElMessageBox.prompt(
        `此脚本属于「危险」级别，操作可能不可恢复或对外部有副作用。\n` +
        `请输入 ${RISK_CONFIRM_TOKEN} 以确认执行：`,
        '危险脚本确认',
        {
          inputPattern: new RegExp(`^${RISK_CONFIRM_TOKEN}$`),
          inputErrorMessage: `必须输入 ${RISK_CONFIRM_TOKEN}`,
          confirmButtonText: '执行',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
      // ElMessageBox returns { value } in element-plus; normalize across versions.
      confirmToken = (result && (result.value || result)) || RISK_CONFIRM_TOKEN
    } catch {
      return  // user cancelled
    }
  }

  running.value = true
  elapsed.value = 0
  if (elapsedTimer) clearInterval(elapsedTimer)
  elapsedTimer = setInterval(() => { elapsed.value += 1 }, 1000)

  const params = collectParams()
  // Persist last params (key/value form, server path mapped)
  const persistParams = { ...params }
  for (const [k, v] of Object.entries(persistParams)) {
    if (fileInputs[k]) persistParams[k] = fileInputs[k].serverPath
  }
  setItem(LAST_TENANT_KEY, tenantId.value)
  const all = getItem(LAST_PARAMS_KEY, {}) || {}
  all[activeScript.value.id] = persistParams
  setItem(LAST_PARAMS_KEY, all)

  // If batch mode, parse rows and call /batches/execute instead.
  if (batchMode.value) {
    try {
      const rows = parseBatchRows()
      if (!rows.length) {
        ElMessage.warning('未解析到任何批次行')
        running.value = false
        return
      }
      const sum = await runBatch({
        scriptId: activeScript.value.id,
        tenantId: tenantId.value,
        presetId: presetId.value,
        rows,
        // BatchService fans out to executor.execute(); pass the token so each
        // row inherits the dangerous-script authorisation.
        confirmToken: confirmToken || undefined
      })
      // Refresh details for each row's history id
      batchResult.value = sum
      const detailList = await getBatch(sum.batchId).catch(() => sum.historyIds.map((id) => ({ id, scriptName: activeScript.value.displayName, tenantName: '', success: null, status: '—' })))
      batchRowsForTable.value = detailList || []
      batchResultOpen.value = true
      recentScripts(6).then((r) => { recentList.value = r || [] }).catch(() => {})
    } catch (_) { /* interceptor surfaced */ }
    finally {
      running.value = false
      if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null }
    }
    return
  }

  try {
    const payload = {
      scriptId: activeScript.value.id,
      tenantId: tenantId.value,
      params,
      presetId: presetId.value,
      fileInputs: Object.fromEntries(
        Object.entries(fileInputs).map(([k, v]) => [k, v.serverPath])
      )
    }
    if (confirmToken) payload.confirmToken = confirmToken
    const history = await execute(payload)
    resultHistory.value = history
    try {
      const [so, se] = await Promise.all([
        readStdout(history.id).catch(() => ''),
        readStderr(history.id).catch(() => '')
      ])
      resultStdout.value = so || ''
      resultStderr.value = se || ''
    } catch {
      resultStdout.value = ''
      resultStderr.value = ''
    }
    recentScripts(6).then((r) => { recentList.value = r || [] }).catch(() => {})
  } catch (_) {
    // axios interceptor already toasted
  } finally {
    running.value = false
    if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null }
  }
}

async function rerunFromResult() {
  if (!resultHistory.value) return
  // Re-run with the same params from the just-finished history (these are
  // exactly what was persisted to localStorage).
  await runScript()
}

async function toggleFavorite(s, val) {
  await setScriptFavorite(s.id, val)
  s.favorite = val
}

onMounted(async () => {
  await refreshAll()
  // Compute responsive drawer size
  const compute = () => { drawerSize.value = window.innerWidth >= 1280 ? '560px' : '460px' }
  compute()
  window.addEventListener('resize', compute)
  onUnmounted(() => window.removeEventListener('resize', compute))
  // Consume a possible rerun handoff from HistoryView (sessionStorage)
  await consumeRerun()
})
</script>

<style scoped>
.sb-toolbar { margin-bottom: 16px; }
.sb-search { max-width: 420px; }

.sb-block { margin-bottom: 24px; }

.sb-title-tag { margin-left: 8px; vertical-align: middle; }
.sb-group-title {
  cursor: pointer;
  user-select: none;
  padding: 2px 0;
}

.sb-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

/* 最近使用: thinner rows, not full cards */
.sb-script-card-recent {
  background: var(--sb-bg-panel);
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 10px 12px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 56px;
}
.sb-script-card-recent:hover { border-color: var(--sb-primary); }
.sb-recent-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.sb-recent-name { font-weight: 600; font-size: 13.5px; color: var(--sb-text); }
.sb-recent-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--sb-text-3);
}
.sb-recent-meta .dot { color: var(--sb-text-3); }

.sb-drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.sb-drawer-title { font-size: 16px; font-weight: 600; }

.sb-form-section { margin-bottom: 18px; }
.sb-form-section-label {
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 6px;
  color: var(--sb-text);
}

.sb-file-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px dashed var(--sb-border);
}
.sb-file-row:last-child { border-bottom: 0; }
.sb-file-row-label {
  font-weight: 500;
  min-width: 100px;
}
.sb-file-name {
  color: var(--sb-text-3);
  font-family: var(--sb-mono);
  font-size: 12px;
  flex: 1;
}

.sb-cmd {
  background: #fafafa;
  font-size: 12px;
  word-break: break-all;
  white-space: pre-wrap;
}
.sb-preview-meta {
  margin-top: 12px;
  font-size: 13px;
  color: var(--sb-text-2);
  display: grid;
  gap: 4px;
}
</style>