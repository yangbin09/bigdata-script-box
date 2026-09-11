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
            <ParamForm
              ref="formRef"
              :params="activeParams"
              :initial-values="lastParamsForScript"
              v-model="formValues"
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
              <el-button @click="closeDrawer" :disabled="running">取消</el-button>
              <el-button
                type="primary"
                :icon="running ? Loading : VideoPlay"
                :loading="running"
                :disabled="!tenantId"
                @click="runScript"
              >{{ running ? `执行中… ${elapsed}s` : '执行脚本' }}</el-button>
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
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Refresh, Search, VideoPlay, Loading, Close, ArrowRight, ArrowDown,
  EditPen, RefreshRight
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listScripts, getScript, setScriptFavorite } from '../api/scripts'
import { listTenants } from '../api/tenants'
import { execute, readStdout, readStderr } from '../api/executions'
import { recentScripts } from '../api/history'
import ScriptCard from '../components/ScriptCard.vue'
import ParamForm from '../components/ParamForm.vue'
import ExecutionResultPanel from '../components/ExecutionResultPanel.vue'
import { formatDateTime } from '../utils/format'
import { getItem, setItem, removeItem } from '../utils/storage'

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
  try {
    const detail = await getScript(s.id)
    activeParams.value = detail?.params || []
  } catch {
    activeParams.value = []
  }
  // Seed defaults (handled inside ParamForm via initialValues)
  formValues.value = {}
  tenantId.value = pickInitialTenant(s)
  drawerOpen.value = true
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
  running.value = true
  elapsed.value = 0
  if (elapsedTimer) clearInterval(elapsedTimer)
  elapsedTimer = setInterval(() => { elapsed.value += 1 }, 1000)

  const params = {}
  for (const [k, v] of Object.entries(formValues.value)) {
    if (v === '' || v == null) continue
    params[k] = String(v)
  }
  try {
    const history = await execute(activeScript.value.id, tenantId.value, params)
    resultHistory.value = history
    // Persist: last tenant + last params
    setItem(LAST_TENANT_KEY, tenantId.value)
    const all = getItem(LAST_PARAMS_KEY, {}) || {}
    all[activeScript.value.id] = params
    setItem(LAST_PARAMS_KEY, all)
    // Fetch logs in parallel; both may be empty for trivial scripts.
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
    // Refresh recent scripts in the background so the "最近使用" block updates
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
</style>