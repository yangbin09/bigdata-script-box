<!--
  ScriptExecutionDrawer — 全站唯一的「执行一个脚本」交互。

  v2 简化要点（对应 SIMPLIFICATION.md 的 P0-B / P1-A / P2）：

  1. 「重跑上次」优先 —— 成功执行的参数本身就是一套可用方案，由后端
     GET /api/history/plan 从历史推导（见 ScriptPlan）。用户不再需要先建 Preset。
     抽屉顶部一行 chip 显示「上次成功 · 2 小时前」，点它即可回填。
  2. 删掉「4 来源徽章 + 撤销倒计时」—— 那是把数据库的来源投影到 UI，而且
     「撤销 (5s)」把容错成本转嫁给用户。现在只有默认值预填 + 一个「恢复默认」。
  3. 参数折叠：有默认值且未改动的参数默认收起（ParamForm collapsible）。
  4. 实时 tail / 终态轮询 / 读失败状态抽到 composables/useLiveLog。
  5. 租户选择器在只有一个启用租户时折叠成一行 —— 实测 108/108 次执行
     都用的同一个租户，每次占一屏是浪费。
-->
<template>
  <el-drawer
    :model-value="modelValue"
    :direction="'rtl'"
    :size="drawerSize"
    :destroy-on-close="false"
    :show-close="false"
    :wrapper-closable="false"
    class="sb-exec-drawer"
    @update:model-value="(v) => $emit('update:modelValue', v)"
  >
    <template #header>
      <div class="sb-drawer-header">
        <div>
          <div class="sb-drawer-title">
            {{ script?.displayName || script?.name }}
            <el-tag
              v-if="script"
              size="small" disable-transitions effect="plain"
              :type="RISK_LEVEL_TAG_TYPE[normalizeRiskLevel(script.riskLevel)]"
              class="sb-title-tag"
            >{{ RISK_LEVEL_LABEL[normalizeRiskLevel(script.riskLevel)] }}</el-tag>
          </div>
          <div v-if="script" class="sb-exec-sub">
            <span>{{ script.category || '默认' }}</span>
            <span class="dot">·</span>
            <span>超时 {{ script.timeoutSeconds || 600 }} 秒</span>
            <span class="dot">·</span>
            <router-link
              :to="{ name: 'script-edit', query: { id: script.id } }"
              class="sb-exec-edit-link"
              target="_blank"
            >查看脚本定义 →</router-link>
          </div>
        </div>
        <el-button text :icon="Close" @click="requestClose" />
      </div>
    </template>

    <template v-if="script">
      <div class="sb-exec-split" :class="{ 'sb-exec-narrow': narrowScreen }">
        <!-- 左：表单（运行中 disabled） -->
        <div class="sb-exec-left">
          <!-- 租户：只有一个启用租户时折成一行，不再占一屏 -->
          <div class="sb-form-section">
            <div class="sb-form-section-label">租户</div>
            <div v-if="enabledTenants.length <= 1" class="sb-tenant-line">
              <el-tag size="small" effect="plain" disable-transitions>
                {{ enabledTenants[0]?.name || '未配置租户' }}
              </el-tag>
              <span class="muted">{{ enabledTenants[0]?.principal || '' }}</span>
            </div>
            <el-select v-else v-model="tenantId" :disabled="running" style="width: 100%">
              <el-option
                v-for="t in enabledTenants"
                :key="t.id"
                :label="`${t.name}（${t.principal || '-'}）`"
                :value="t.id"
              />
            </el-select>
          </div>

          <!-- 「上次成功」：由历史推导，无需用户先建 Preset -->
          <div v-if="plan.executionId" class="sb-form-section">
            <div class="sb-plan-chip">
              <el-icon><Timer /></el-icon>
              <span class="sb-plan-text">
                上次成功：{{ formatRelative(plan.executedAt) }}
                <span v-if="plan.durationMs != null" class="muted">· 耗时 {{ formatDuration(plan.durationMs) }}</span>
              </span>
              <el-button size="small" text type="primary" :disabled="running" @click="applyPlan">
                用这套参数
              </el-button>
            </div>
          </div>

          <!-- 多套参数方案（可选；不选也能跑，历史里那套已经是默认方案） -->
          <div v-if="presets.length" class="sb-form-section">
            <div class="sb-form-section-label">
              已保存的方案 <span class="muted">(可选)</span>
            </div>
            <el-select
              v-model="presetId"
              :disabled="running"
              clearable
              placeholder="不指定"
              style="width: 100%"
              @change="applyPresetToForm"
            >
              <el-option v-for="p in presets" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
          </div>

          <div class="sb-form-section">
            <ParamForm
              ref="formRef"
              :params="activeParams"
              :initial-values="seedValues"
              collapsible
              v-model="formValues"
            />
          </div>

          <!-- 文件参数 -->
          <div v-if="fileParamNames.length" class="sb-form-section">
            <div class="sb-form-section-label">文件参数</div>
            <div v-for="p in fileParamNames" :key="p" class="sb-file-row">
              <div class="sb-file-row-label">{{ p }}</div>
              <el-upload
                :auto-upload="true"
                :show-file-list="false"
                :http-request="(opts) => uploadFileFor(opts.file, p)"
                :before-upload="beforeUploadFile"
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

          <!-- 批量执行：收进折叠区，避免"模式切换"污染主流程 -->
          <div v-if="allowBatch" class="sb-form-section">
            <el-collapse @change="(names) => (batchMode = names.includes('batch'))">
              <el-collapse-item name="batch">
                <template #title>
                  <span class="sb-advanced-title">批量执行</span>
                  <span class="muted sb-advanced-hint">按行跑多次（Excel 粘贴）</span>
                </template>
                <BatchTableEditor
                  ref="batchEditorRef"
                  v-model="batchRows"
                  :params="script?.params || []"
                  :disabled="running"
                  @update:invalid-count="(n) => (batchInvalidCount = n)"
                />
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>

        <!-- 右：实时日志 → 执行结果 -->
        <div class="sb-exec-right">
          <div class="sb-exec-right-header">
            <span v-if="live.running.value">
              执行中 · {{ elapsed }}s · #{{ live.executionId.value }}
            </span>
            <span v-else-if="resultHistory">
              结果 · #{{ resultHistory.id }} · {{ formatDateTime(resultHistory.startTime) }}
            </span>
            <span v-else class="muted">日志 / 结果</span>
          </div>

          <div v-if="!resultHistory && live.running.value" class="sb-live-log">
            <div v-if="live.error.value" class="sb-log-error">
              <el-alert type="error" :closable="false" show-icon>
                读取实时日志失败
                <el-button size="small" text @click="live.refreshTail">重试</el-button>
              </el-alert>
            </div>
            <pre v-if="live.text.value" class="sb-log-stdout">{{ live.text.value }}</pre>
            <div v-else-if="!live.error.value" class="sb-log sb-log-empty">脚本尚未产生 stdout 输出…</div>
          </div>

          <ExecutionResultPanel
            v-else-if="resultHistory"
            :history="resultHistory"
            :log-stdout="resultStdout"
            :log-stderr="resultStderr"
            :log-stdout-error="stdoutError"
            :log-stderr-error="stderrError"
            @rerun="rerunFromResult"
            @edit="backToForm"
            @retry-logs="loadLogs"
          />

          <div v-else class="sb-right-empty muted">
            提交后会在这里实时显示日志，完成后展示结果摘要。
          </div>
        </div>
      </div>
    </template>

    <template #footer>
      <div v-if="script" class="sb-exec-footer">
        <!-- 表单页 -->
        <template v-if="!resultHistory">
          <el-button :disabled="running" @click="resetDefaults">恢复默认</el-button>
          <div class="right">
            <el-button v-if="allowPreview" @click="$emit('preview')" :disabled="running">
              预览命令
            </el-button>
            <el-button @click="requestClose" :disabled="running">取消</el-button>
            <el-button
              v-if="running"
              type="danger" plain
              :loading="cancelling"
              :icon="CircleClose"
              @click="cancelRunning"
            >取消执行</el-button>
            <el-button
              type="primary"
              :icon="running ? Loading : VideoPlay"
              :loading="running"
              :disabled="!tenantId"
              @click="runScript"
            >{{ running ? `执行中… ${elapsed}s` : (batchMode ? '批量执行' : '执行') }}</el-button>
          </div>
        </template>
        <!-- 结果页：执行完成后 Drawer 不关闭 -->
        <template v-else>
          <el-button @click="requestClose">关闭</el-button>
          <div class="right">
            <el-button v-if="allowQuickSave" :icon="Star" @click="$emit('save-quick')">存为方案</el-button>
            <el-button :icon="Clock" plain @click="goHistory">查看历史</el-button>
            <el-button :icon="EditPen" @click="backToForm">修改参数</el-button>
            <el-button :icon="RefreshRight" type="primary" @click="rerunFromResult">再次执行</el-button>
          </div>
        </template>
      </div>
    </template>
  </el-drawer>

  <!-- 批量执行结果 -->
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
        <div class="sb-status-headline">
          成功 {{ batchResult.succeeded }} / 失败 {{ batchResult.failed }} / 总 {{ batchResult.total }}
        </div>
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
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="{ $index }">
            <el-button
              size="small" link type="primary"
              :loading="retryingRow === $index"
              @click="retryBatchRow($index)"
            >重试此行</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Close, VideoPlay, Loading, CircleClose, EditPen, RefreshRight,
  UploadFilled, Timer, Clock, Star
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getScript } from '../api/scripts'
import { listTenants } from '../api/tenants'
import { submitExecution, readStdout, readStderr, cancelExecution } from '../api/executions'
import { getHistory, recentScripts, lastPlan } from '../api/history'
import { listPresets, uploadFile, runBatch, getBatch, retryBatchRow as apiRetryBatchRow } from '../api/extras'
import ParamForm from './ParamForm.vue'
import ExecutionResultPanel from './ExecutionResultPanel.vue'
import BatchTableEditor from './BatchTableEditor.vue'
import { formatDateTime, formatBytes, formatDuration } from '../utils/format'
import { setItem, KEYS } from '../utils/storage'
import { useLiveLog } from '../composables/useLiveLog'
import {
  RISK_LEVEL, RISK_LEVEL_LABEL, RISK_LEVEL_TAG_TYPE, normalizeRiskLevel, RISK_CONFIRM_TOKEN
} from '../utils/labels'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 当前脚本（含 params 列表时优先使用，仍会再拉一次完整详情） */
  script: { type: Object, default: null },
  /** 打开时预选租户；不传则自动选择 */
  initialTenantId: { type: [Number, String], default: null },
  /** 打开时预选参数方案 */
  initialPresetId: { type: [Number, String], default: null },
  /** 打开时预填的参数（快捷操作使用） */
  initialParams: { type: Object, default: null },
  /** 打开后自动执行一次 */
  autoRun: { type: Boolean, default: false },
  allowBatch: { type: Boolean, default: false },
  allowPreview: { type: Boolean, default: false },
  allowQuickSave: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'closed', 'finished', 'recent-updated', 'preview', 'save-quick'])

const router = useRouter()
const LAST_TENANT_KEY = KEYS.LAST_TENANT

// ---- 租户 / 参数定义 / 方案 ----
const tenants = ref([])
const activeParams = ref([])
const presets = ref([])
const tenantId = ref(null)
const presetId = ref(null)
const formValues = ref({})
const formRef = ref(null)
const fileInputs = reactive({})

/**
 * 由历史推导的「上次成功方案」。空对象表示该脚本还没有成功记录，
 * 此时表单回退到参数声明里的默认值。
 */
const plan = ref({})

/**
 * 表单种子值。优先级：
 *   1) 调用方显式传入的 initialParams（快捷操作 / 历史重跑）
 *   2) 历史推导出的上次成功参数
 *   3) 参数自身的 defaultValue（ParamForm 内部兜底）
 *
 * ParamForm 用内容签名判断是否需要重新播种，所以这里返回普通对象即可。
 */
const seedValues = computed(() => {
  if (props.initialParams && Object.keys(props.initialParams).length) return props.initialParams
  return plan.value.parameters || {}
})

const enabledTenants = computed(() => (tenants.value || []).filter((t) => t.enabled !== false))
const fileParamNames = computed(() =>
  (activeParams.value || []).filter((p) => p.type === 'file').map((p) => p.name)
)

// ---- 运行状态 ----
const running = ref(false)
const elapsed = ref(0)
let elapsedTimer = null
const cancelling = ref(false)

/**
 * 「打开后自动执行一次」的内部开关。
 *
 * <p>为什么不在 props 上直接改：{@code autoRun} 是父组件的 prop，子组件改它
 * 会告警且不可靠；而 {@code load()} 有可能被 {@code watch(script.id)} 再触发
 * 一次，一次性开关必须能被真正消费掉，否则会提交两次。
 */
const autoRunFlag = ref(false)

// 实时 tail + 终态轮询（抽到 composable，任何入口都能复用）
const live = useLiveLog()

// ---- 结果 ----
const resultHistory = ref(null)
const resultStdout = ref('')
const resultStderr = ref('')
// 读取失败与"真的为空"必须分开：前者不能渲染成「无 stdout 输出」
const stdoutError = ref('')
const stderrError = ref('')
let disposed = false

// ---- 批量 ----
const batchMode = ref(false)
const batchRows = ref([])
const batchEditorRef = ref(null)
const batchInvalidCount = ref(0)
const batchResult = ref(null)
const batchResultOpen = ref(false)
const batchRowsForTable = ref([])
const retryingRow = ref(-1)

// ---- 抽屉尺寸 / 窄屏 ----
const drawerSize = ref('560px')
const narrowScreen = ref(false)
function computeDrawerSize() { drawerSize.value = window.innerWidth >= 1280 ? '560px' : '460px' }
function checkNarrow() { narrowScreen.value = typeof window !== 'undefined' && window.innerWidth < 1024 }

// ======================================================================
// 打开 / 关闭
// ======================================================================

function pickInitialTenant(script) {
  if (props.initialTenantId && enabledTenants.value.some((t) => t.id === props.initialTenantId)) {
    return props.initialTenantId
  }
  if (script?.defaultTenantId && enabledTenants.value.some((t) => t.id === script.defaultTenantId)) {
    return script.defaultTenantId
  }
  return enabledTenants.value.length ? enabledTenants.value[0].id : null
}

async function load() {
  const script = props.script
  if (!script) return
  // 每次打开都重新武装一次性自动执行开关
  autoRunFlag.value = !!props.autoRun
  resultHistory.value = null
  resultStdout.value = ''
  resultStderr.value = ''
  stdoutError.value = ''
  stderrError.value = ''
  plan.value = {}
  elapsed.value = 0
  presetId.value = null
  batchMode.value = false
  batchRows.value = []
  batchInvalidCount.value = 0
  batchResult.value = null
  for (const k of Object.keys(fileInputs)) delete fileInputs[k]

  const [detail, ps, ts] = await Promise.all([
    getScript(script.id).catch(() => null),
    listPresets(script.id).catch(() => []),
    listTenants().catch(() => [])
  ])
  if (disposed) return
  activeParams.value = detail?.params || script.params || []
  presets.value = ps || []
  tenants.value = ts || []
  formValues.value = {}
  tenantId.value = pickInitialTenant(detail || script)

  // 方案推导：拿到租户后再查，让「上次成功」能按租户隔离
  await refreshPlan()

  if (props.initialPresetId) {
    presetId.value = props.initialPresetId
    await applyPresetToForm(props.initialPresetId)
  }
  // autoRun 是一次性的：load() 可能被 watch(script.id) 再触发一次，
  // 若不清零就会提交两次（第二次会被后端"已有运行中的执行"拒绝，
  // 用户看到的是"失败 Exit -1"而不是成功的那个结果）。
  if (autoRunFlag.value) {
    autoRunFlag.value = false
    await waitFormReady()
    await runScript()
  }
}

/**
 * 等参数真正播种进表单再自动执行。
 *
 * <p>ParamForm 是异步挂载 + 异步播种的，若在它回传 seed 之前就提交，
 * 脚本会收到空参数（实测：`{"name":"world"}` 变成 `{}`，脚本里
 * `${NAME:-world}` 因而回退到默认值，用户很难发现）。
 */
async function waitFormReady() {
  for (let i = 0; i < 12; i++) {
    if (Object.keys(formValues.value || {}).length) return
    await new Promise((r) => setTimeout(r, 50))
  }
}

/** 拉取「上次成功方案」。失败不阻断（回退默认值即可）。 */
async function refreshPlan() {
  if (!props.script) return
  try {
    const p = await lastPlan(props.script.id, tenantId.value)
    if (!disposed) plan.value = p || {}
  } catch (_) {
    if (!disposed) plan.value = {}
  }
}

/** 一键回填历史那套参数。 */
function applyPlan() {
  if (!plan.value?.parameters) return
  formValues.value = { ...plan.value.parameters }
  if (plan.value.tenantId && enabledTenants.value.some((t) => t.id === plan.value.tenantId)) {
    tenantId.value = plan.value.tenantId
  }
  ElMessage.success('已填入上次成功的参数')
}

watch(() => props.modelValue, async (open, was) => {
  if (open) {
    await load()
  } else if (was) {
    live.stop()
    emit('closed')
  }
})

watch(() => props.script?.id, async (id, prev) => {
  if (id && id !== prev && props.modelValue) await load()
})

watch(tenantId, () => {
  // 换租户后「上次成功」应重新推导（不同租户参数往往不同）
  if (props.modelValue) refreshPlan()
})

onUnmounted(() => {
  window.removeEventListener('resize', computeDrawerSize)
  window.removeEventListener('resize', checkNarrow)
  disposed = true
  if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null }
  live.stop()
})
window.addEventListener('resize', computeDrawerSize)
window.addEventListener('resize', checkNarrow)
computeDrawerSize()
checkNarrow()

function requestClose() {
  if (running.value) {
    ElMessage.warning('脚本正在执行，无法关闭')
    return
  }
  emit('update:modelValue', false)
}

function goHistory() {
  emit('update:modelValue', false)
  router.push('/history')
}

async function backToForm() {
  resultHistory.value = null
  resultStdout.value = ''
  resultStderr.value = ''
  stdoutError.value = ''
  stderrError.value = ''
}

function resetDefaults() { formRef.value?.resetToDefaults?.() }

function formatRelative(ts) {
  if (!ts) return ''
  const then = new Date(ts).getTime()
  if (Number.isNaN(then)) return ''
  const diff = Date.now() - then
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}

// ======================================================================
// 表单
// ======================================================================

async function applyPresetToForm(pid) {
  if (!pid) return
  const p = presets.value.find((x) => String(x.id) === String(pid))
  if (!p) return
  let parsed = {}
  try { parsed = p.paramsJson ? JSON.parse(p.paramsJson) : {} } catch { /* tolerate */ }
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
    fileInputs[paramName] = { serverPath: res.path, originalName: file.name, bytes: file.size }
    ElMessage.success(`${paramName} 上传完成`)
  } catch (_) { /* interceptor already toasted */ }
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

// ======================================================================
// 执行
// ======================================================================

function startElapsed() {
  elapsed.value = 0
  if (elapsedTimer) clearInterval(elapsedTimer)
  elapsedTimer = setInterval(() => { elapsed.value += 1 }, 1000)
}
function stopElapsed() {
  if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null }
}

/** 把「读成功」与「读失败」分开返回，调用方不再用 '' 混淆两者。 */
async function safeRead(fn, id) {
  try {
    const text = await fn(id)
    return { ok: true, text: typeof text === 'string' ? text : '', error: '' }
  } catch (e) {
    return { ok: false, text: '', error: e?.message || '读取失败' }
  }
}

async function loadLogs() {
  const id = live.executionId.value ?? resultHistory.value?.id
  if (!id) return
  const [so, se] = await Promise.all([safeRead(readStdout, id), safeRead(readStderr, id)])
  if (disposed) return
  resultStdout.value = so.text
  stdoutError.value = so.error
  resultStderr.value = se.text
  stderrError.value = se.error
}

async function finishExecution(id) {
  running.value = false
  stopElapsed()
  // 1) 完整执行详情。store 里的视图只有 6 个字段，结果面板需要
  //    durationMs / parametersJson / scriptName，所以必须重拉 /history/{id}。
  try {
    const full = await getHistory(id)
    if (full && !disposed) resultHistory.value = full
  } catch (e) {
    if (!disposed) ElMessage.warning(`执行详情读取失败：${e?.message || '未知错误'}`)
  }
  // 2) 日志（失败显式标记，不伪装成空）
  if (resultHistory.value) {
    const [so, se] = await Promise.all([safeRead(readStdout, id), safeRead(readStderr, id)])
    if (!disposed) {
      resultStdout.value = so.text
      stdoutError.value = so.error
      resultStderr.value = se.text
      stderrError.value = se.error
    }
  }
  if (!disposed) {
    emit('finished', resultHistory.value)
    recentScripts(6).then((r) => emit('recent-updated', r || [])).catch(() => {})
    // 本次成功会让「上次成功方案」发生变化，静默刷新一次
    refreshPlan()
  }
}

async function runScript() {
  if (!props.script || !formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  // 危险脚本二次确认
  const riskLevel = normalizeRiskLevel(props.script.riskLevel)
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
      confirmToken = (result && (result.value || result)) || RISK_CONFIRM_TOKEN
    } catch {
      return
    }
  }

  running.value = true
  startElapsed()
  backToForm()

  const params = collectParams()
  setItem(LAST_TENANT_KEY, tenantId.value)

  // 批量模式（由折叠区开关决定）
  if (batchMode.value) {
    try {
      const editor = batchEditorRef.value
      if (editor && typeof editor.validate === 'function') editor.validate()
      const rows = parseBatchRows()
      if (!rows.length) {
        ElMessage.warning(batchInvalidCount.value
          ? `全部 ${batchInvalidCount.value} 行校验失败，无法提交。`
          : '未解析到任何批次行')
        return
      }
      const sum = await runBatch({
        scriptId: props.script.id,
        tenantId: tenantId.value,
        presetId: presetId.value,
        rows,
        confirmToken: confirmToken || undefined
      })
      batchResult.value = sum
      const detailList = await getBatch(sum.batchId).catch(() =>
        (sum.historyIds || []).map((id) => ({
          id, scriptName: props.script.displayName, tenantName: '', success: null, status: '—'
        })))
      batchRowsForTable.value = detailList || []
      batchResultOpen.value = true
    } catch (_) { /* interceptor surfaced */ } finally {
      running.value = false
      stopElapsed()
    }
    return
  }

  try {
    const payload = {
      scriptId: props.script.id,
      tenantId: tenantId.value,
      params,
      presetId: presetId.value,
      fileInputs: Object.fromEntries(Object.entries(fileInputs).map(([k, v]) => [k, v.serverPath]))
    }
    if (confirmToken) payload.confirmToken = confirmToken

    const accepted = await submitExecution(payload)
    const id = accepted?.executionId ?? accepted?.id
    if (!id) throw new Error('后端未返回 executionId')

    const waitMs = ((Number(props.script.timeoutSeconds || 600) + 60) * 1000) + 60000
    // live.track 负责 tail + 终态轮询，并返回可等待的 Promise（不依赖 store 节奏）
    live.track(id, waitMs).then(() => finishExecution(id))
  } catch (e) {
    running.value = false
    stopElapsed()
    live.stop()
    if (e?.message) ElMessage.error(e.message)
  }
}

function parseBatchRows() {
  if (!batchRows.value || !batchRows.value.length) return []
  const editor = batchEditorRef.value
  if (editor && typeof editor.validRows === 'function') {
    try {
      return editor.validRows().map((r) => {
        const o = {}
        for (const [k, v] of Object.entries(r)) if (v !== '' && v != null) o[k] = String(v)
        return o
      })
    } catch { /* fall through */ }
  }
  return batchRows.value
}

async function retryBatchRow(rowIndex) {
  if (!batchResult.value?.batchId) return
  retryingRow.value = rowIndex
  try {
    await apiRetryBatchRow(batchResult.value.batchId, rowIndex, true)
    const detailList = await getBatch(batchResult.value.batchId).catch(() => [])
    batchRowsForTable.value = detailList || []
    const succeeded = batchRowsForTable.value.filter((r) => r.success).length
    batchResult.value = { ...batchResult.value, succeeded, failed: batchRowsForTable.value.length - succeeded }
    ElMessage.success(`第 ${rowIndex + 1} 行已重新提交`)
  } catch (err) {
    ElMessage.error(`重试失败：${err?.message || '未知错误'}`)
  } finally {
    retryingRow.value = -1
  }
}

async function rerunFromResult() {
  await runScript()
}

async function cancelRunning() {
  if (!running.value || cancelling.value) return
  const id = live.executionId.value
  if (!id) return
  cancelling.value = true
  try {
    await cancelExecution(id)
    ElMessage.success(`已请求取消 #${id}`)
  } catch (_) { /* interceptor already toasted */ } finally {
    cancelling.value = false
  }
}

defineExpose({
  run: runScript,
  /** 当前表单快照（父组件做 dry-run 预览 / 存参数方案时使用）。 */
  snapshot: () => ({
    tenantId: tenantId.value,
    presetId: presetId.value,
    params: collectParams()
  }),
  reload: load
})
</script>

<style scoped>
.sb-title-tag { margin-left: 8px; vertical-align: middle; }
.sb-file-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.sb-file-row-label { min-width: 96px; font-size: 12.5px; color: var(--sb-text-2); }
.sb-file-name { font-size: 12px; color: var(--sb-text-2); }
.sb-log-error { margin-bottom: 8px; }

/* 单租户时的一行展示 */
.sb-tenant-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
}

/* 「上次成功」chip：由历史推导，不是用户创建的方案 */
.sb-plan-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  background: #f8fafc;
  font-size: 12.5px;
}
.sb-plan-text { flex: 1; min-width: 0; }

/* 高级区标题 */
.sb-advanced-title { font-size: 13px; }
.sb-advanced-hint { margin-left: 8px; font-size: 12px; }
</style>
