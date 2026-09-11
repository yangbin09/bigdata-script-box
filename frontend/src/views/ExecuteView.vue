<!--
  ExecuteView — the front page.
  - Groups enabled scripts by category, renders each as a card.
  - Clicking a card opens a Drawer with the script's dynamic parameter form.
  - Submitting the drawer runs the script; result shown in an ElDialog with status
    card + Tabs (stdout / stderr / params).
-->
<template>
  <div>
    <div class="sb-header">
      <div>
        <h2 class="sb-page-title">执行中心</h2>
        <p class="sb-page-sub">按分类浏览脚本，点击卡片配置参数后执行。</p>
      </div>
      <el-button :icon="Refresh" plain @click="refreshAll" :loading="loading">刷新</el-button>
    </div>

    <el-empty v-if="!loading && !scripts.length" description="暂无可执行脚本，先去「脚本管理」创建。" />

    <section
      v-for="group in grouped"
      :key="group.category"
      class="sb-group"
    >
      <h3 class="sb-section-title">
        {{ group.category }}
        <span class="count">{{ group.scripts.length }}</span>
      </h3>
      <div class="sb-card-grid">
        <div
          v-for="s in group.scripts"
          :key="s.id"
          class="sb-script-card"
          :class="{ disabled: !s.enabled }"
          @click="openDrawer(s)"
        >
          <div class="sb-script-card-head">
            <div class="sb-script-name">{{ s.displayName || s.name }}</div>
            <el-tag
              size="small"
              :type="s.enabled ? 'success' : 'info'"
              effect="plain"
              disable-transitions
            >{{ s.enabled ? 'enabled' : 'disabled' }}</el-tag>
          </div>
          <div class="sb-script-desc">{{ s.description || '—' }}</div>
          <div class="sb-script-meta">
            <span><el-icon><Timer /></el-icon> {{ s.timeoutSeconds || 600 }}s</span>
            <span class="sb-script-name-mono">{{ s.name }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- Drawer: parameter form for the selected script -->
    <el-drawer
      v-model="drawerOpen"
      :title="activeScript ? `执行 — ${activeScript.displayName || activeScript.name}` : '执行'"
      direction="rtl"
      size="480px"
      :destroy-on-close="false"
    >
      <template v-if="activeScript">
        <div class="sb-drawer-meta">
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="脚本">{{ activeScript.name }}</el-descriptions-item>
            <el-descriptions-item label="分类">{{ activeScript.category || '—' }}</el-descriptions-item>
            <el-descriptions-item label="超时">{{ activeScript.timeoutSeconds || 600 }} 秒</el-descriptions-item>
            <el-descriptions-item label="租户">
              <el-select
                v-model="tenantId"
                placeholder="选择租户"
                size="small"
                style="width: 100%"
              >
                <el-option
                  v-for="t in enabledTenants"
                  :key="t.id"
                  :label="`${t.name} (${t.principal})`"
                  :value="t.id"
                />
              </el-select>
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <ParamForm
          ref="formRef"
          :params="activeParams"
          v-model="formValues"
        />

        <div class="sb-drawer-footer">
          <el-button @click="drawerOpen = false">取消</el-button>
          <el-button
            type="primary"
            :icon="VideoPlay"
            :loading="running"
            :disabled="!tenantId"
            @click="runScript"
          >执行</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- Result dialog -->
    <ExecutionResultDialog
      v-model="resultOpen"
      :history="lastResult"
      :loading="loadingResult"
      :log-stdout="resultStdout"
      :log-stderr="resultStderr"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Refresh, VideoPlay, Timer } from '@element-plus/icons-vue'
import { listScripts, getScript } from '../api/scripts'
import { listTenants } from '../api/tenants'
import { execute, readStdout, readStderr } from '../api/executions'
import { ElMessage } from 'element-plus'
import ParamForm from '../components/ParamForm.vue'
import ExecutionResultDialog from '../components/ExecutionResultDialog.vue'

const scripts = ref([])
const tenants = ref([])
const loading = ref(false)

const drawerOpen = ref(false)
const activeScript = ref(null)
const activeParams = ref([])
const formValues = ref({})
const tenantId = ref(null)
const formRef = ref(null)
const running = ref(false)

const resultOpen = ref(false)
const lastResult = ref(null)
const resultStdout = ref('')
const resultStderr = ref('')
const loadingResult = ref(false)

const enabledTenants = computed(() =>
  (tenants.value || []).filter((t) => t.enabled !== false)
)

const grouped = computed(() => {
  const map = new Map()
  for (const s of scripts.value) {
    const k = s.category || '默认'
    if (!map.has(k)) map.set(k, [])
    map.get(k).push(s)
  }
  return [...map.entries()].map(([category, list]) => ({
    category,
    scripts: list
  }))
})

async function refreshAll() {
  loading.value = true
  try {
    const [s, t] = await Promise.all([listScripts(), listTenants()])
    scripts.value = s || []
    tenants.value = t || []
  } finally {
    loading.value = false
  }
}

async function openDrawer(s) {
  if (!s.enabled) {
    ElMessage.warning('脚本已禁用，无法执行')
    return
  }
  activeScript.value = s
  // Fetch full detail (params + body isn't needed here, but params are)
  try {
    const detail = await getScript(s.id)
    activeParams.value = detail?.params || []
  } catch {
    activeParams.value = []
  }
  // Seed defaults
  const seed = {}
  for (const p of activeParams.value) {
    if (p.defaultValue != null) seed[p.name] = p.defaultValue
    else if (p.type === 'boolean') seed[p.name] = 'false'
    else seed[p.name] = ''
  }
  formValues.value = seed
  tenantId.value = enabledTenants.value[0]?.id ?? null
  drawerOpen.value = true
}

async function runScript() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  running.value = true
  // Coerce values: numbers back to strings (backend validation is strict)
  const params = {}
  for (const [k, v] of Object.entries(formValues.value)) {
    if (v === '' || v == null) continue
    params[k] = String(v)
  }
  try {
    const history = await execute(activeScript.value.id, tenantId.value, params)
    lastResult.value = history
    drawerOpen.value = false
    resultOpen.value = true
    loadingResult.value = true
    // Fetch logs in parallel — both may be empty for trivial scripts.
    const [so, se] = await Promise.all([
      readStdout(history.id).catch(() => ''),
      readStderr(history.id).catch(() => '')
    ])
    resultStdout.value = so || ''
    resultStderr.value = se || ''
  } catch (e) {
    // error already toasted by axios interceptor
  } finally {
    running.value = false
    loadingResult.value = false
  }
}

onMounted(refreshAll)
</script>

<style scoped>
.sb-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 16px;
}

.sb-page-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.sb-page-sub {
  margin: 4px 0 0 0;
  color: var(--sb-text-3);
  font-size: 13px;
}

.sb-group { margin-bottom: 28px; }

.sb-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}

.sb-script-card {
  background: var(--sb-bg-panel);
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 14px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 130px;
}

.sb-script-card:hover {
  border-color: var(--sb-primary);
}

.sb-script-card.disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.sb-script-card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.sb-script-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--sb-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sb-script-desc {
  font-size: 12.5px;
  color: var(--sb-text-2);
  line-height: 1.5;
  flex: 1;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.sb-script-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11.5px;
  color: var(--sb-text-3);
}

.sb-script-meta span {
  display: flex;
  align-items: center;
  gap: 3px;
}

.sb-script-name-mono {
  font-family: var(--sb-mono);
}

.sb-drawer-meta { margin-bottom: 16px; }

.sb-drawer-footer {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  border-top: 1px solid var(--sb-border);
  padding-top: 16px;
}
</style>