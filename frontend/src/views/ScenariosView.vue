<!--
  ScenariosView — simple scenario orchestration: ordered list of script steps.
  Each scenario can have many steps, each referencing a script (and optional preset).
-->
<template>
  <div>
    <div class="sb-header">
      <div>
        <h2 class="sb-page-title">场景编排</h2>
        <p class="sb-page-sub">把多个脚本按顺序串成一个场景：步骤成功才执行下一步，失败默认中止。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增场景</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" class="sb-card" stripe>
      <el-table-column label="ID" width="60" prop="id" />
      <el-table-column label="名称" min-width="180">
        <template #default="{ row }">
          <div class="sb-name-main">{{ row.name }}</div>
        </template>
      </el-table-column>
      <el-table-column label="分类" width="120" prop="category">
        <template #default="{ row }">
          <el-tag v-if="row.category" size="small" disable-transitions>{{ row.category }}</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="描述" min-width="240" prop="description" show-overflow-tooltip />
      <el-table-column label="步骤数" width="90" align="right">
        <template #default="{ row }">
          <span class="mono">{{ stepCounts[row.id] ?? '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.enabled === false ? 'info' : 'success'"
            disable-transitions
            effect="plain"
          >{{ row.enabled === false ? '已禁用' : '已启用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="320" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" :icon="VideoPlay" @click="openRun(row)">运行</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" plain @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Edit drawer -->
    <el-drawer
      v-model="formOpen"
      :title="form.id ? '编辑场景' : '新增场景'"
      direction="rtl"
      :size="'720px'"
      :show-close="false"
      class="sb-exec-drawer"
    >
      <template #header>
        <div class="sb-drawer-header">
          <div class="sb-drawer-title">{{ form.id ? '编辑场景' : '新增场景' }}</div>
          <el-button text :icon="Close" @click="formOpen = false" />
        </div>
      </template>

      <el-form :model="form" label-position="top">
        <div class="sb-edit-grid">
          <el-form-item label="名称" required>
            <el-input v-model="form.name" placeholder="如 每日ETL" />
          </el-form-item>
          <el-form-item label="分类">
            <el-input v-model="form.category" placeholder="如 Mock / ETL" />
          </el-form-item>
        </div>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>

        <div class="sb-steps-head">
          <h3 class="sb-section-title">步骤 <span class="count">{{ steps.length }}</span></h3>
          <el-button size="small" type="primary" plain :icon="Plus" @click="addStep">新增步骤</el-button>
        </div>

        <el-empty v-if="!steps.length" :image-size="60" description="未配置步骤" />

        <div v-for="(s, idx) in steps" :key="idx" class="sb-step-row">
          <div class="sb-step-row-head">
            <span class="sb-step-idx">#{{ idx + 1 }}</span>
            <div class="sb-step-actions">
              <el-button size="small" :icon="Top" :disabled="idx === 0" @click="moveUp(idx)" />
              <el-button size="small" :icon="Bottom" :disabled="idx === steps.length - 1" @click="moveDown(idx)" />
              <el-button size="small" type="danger" plain :icon="Delete" @click="removeStep(idx)">移除</el-button>
            </div>
          </div>
          <el-form label-position="top" :model="s" class="sb-step-form">
            <el-form-item label="脚本" required>
              <el-select v-model="s.scriptId" filterable placeholder="选择脚本" style="width: 100%">
                <el-option
                  v-for="sc in enabledScripts"
                  :key="sc.id"
                  :label="sc.displayName || sc.name"
                  :value="sc.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="参数方案 (preset)">
              <el-select v-model="s.presetId" clearable placeholder="不使用" style="width: 100%">
                <el-option
                  v-for="p in (presetsByScript[s.scriptId] || [])"
                  :key="p.id"
                  :label="p.name"
                  :value="p.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="失败时继续执行">
              <el-switch v-model="s.continueOnFailure" />
            </el-form-item>
          </el-form>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="formOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-drawer>

    <!-- Run drawer -->
    <el-drawer v-model="runOpen" :title="`运行场景 — ${runTarget?.name || ''}`" direction="rtl" size="540px">
      <el-form label-position="top">
        <el-form-item label="租户" required>
          <el-select v-model="runTenantId" placeholder="选择租户" style="width: 100%">
            <el-option v-for="t in enabledTenants" :key="t.id"
              :label="`${t.name}（${t.principal || '-'}）`" :value="t.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="runOpen = false">取消</el-button>
        <el-button type="primary" :loading="running" @click="doRun">运行</el-button>
      </template>
    </el-drawer>

    <!-- Result drawer -->
    <el-drawer v-model="resultOpen" :title="`运行结果 — ${runResult?.scenarioId || ''}`" direction="rtl" size="640px">
      <template v-if="runResult">
        <div class="sb-status-card" :class="runResult.aborted ? 'fail' : 'ok'">
          <div class="sb-status-headline">
            {{ runResult.aborted ? `已中止于第 ${runResult.abortedAtStep + 1} 步` : '全部完成' }}
          </div>
          <div class="sb-status-sub">
            <span>成功 {{ runResult.succeeded }} / 失败 {{ runResult.failed }} / 总 {{ runResult.total }}</span>
          </div>
        </div>
        <el-table :data="runResult.entries" class="sb-card" stripe>
          <el-table-column label="#" width="60" prop="stepNo">
            <template #default="{ row }">#{{ row.stepNo + 1 }}</template>
          </el-table-column>
          <el-table-column label="脚本" min-width="200">
            <template #default="{ row }">
              <span class="mono">{{ scriptNameOf(row.scriptId) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.success ? 'success' : 'danger'" disable-transitions effect="plain">
                {{ row.status || (row.success ? 'SUCCESS' : 'FAILED') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="history" width="100" align="right">
            <template #default="{ row }">
              <span class="mono">{{ row.historyId || '-' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  Plus, VideoPlay, Close, Top, Bottom, Delete
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listScenarios, getScenario, createScenario, updateScenario,
  deleteScenario, replaceScenarioSteps, runScenario,
  listPresets
} from '../api/extras'
import { listScripts } from '../api/scripts'
import { listTenants } from '../api/tenants'

const rows = ref([])
const scripts = ref([])
const tenants = ref([])
const presetsByScript = reactive({})
const stepCounts = reactive({})
const loading = ref(false)

const formOpen = ref(false)
const form = reactive({ id: null, name: '', category: '', description: '', enabled: true })
const steps = ref([])
const saving = ref(false)

const runOpen = ref(false)
const runTarget = ref(null)
const runTenantId = ref(null)
const running = ref(false)

const resultOpen = ref(false)
const runResult = ref(null)

const enabledScripts = computed(() => (scripts.value || []).filter((s) => s.enabled !== false))
const enabledTenants = computed(() => (tenants.value || []).filter((t) => t.enabled !== false))

function scriptNameOf(id) {
  const s = scripts.value.find((x) => x.id === id)
  return s ? (s.displayName || s.name) : `#${id}`
}

async function refresh() {
  loading.value = true
  try {
    rows.value = (await listScenarios()) || []
    for (const k of Object.keys(stepCounts)) delete stepCounts[k]
    await Promise.all(rows.value.map(async (s) => {
      try {
        const d = await getScenario(s.id)
        stepCounts[s.id] = (d.steps || []).length
      } catch { stepCounts[s.id] = 0 }
    }))
  } finally { loading.value = false }
}

function resetForm() {
  Object.assign(form, { id: null, name: '', category: '', description: '', enabled: true })
  steps.value = []
}

function openCreate() {
  resetForm()
  formOpen.value = true
}

async function openEdit(row) {
  resetForm()
  Object.assign(form, {
    id: row.id, name: row.name, category: row.category,
    description: row.description, enabled: row.enabled !== false
  })
  try {
    const d = await getScenario(row.id)
    steps.value = (d.steps || []).map((s) => ({
      scriptId: s.scriptId, presetId: s.presetId || null,
      continueOnFailure: !!s.continueOnFailure
    }))
  } catch { steps.value = [] }
  // Refresh presets for each referenced script
  await loadPresetsForSteps()
  formOpen.value = true
}

async function loadPresetsForSteps() {
  const ids = [...new Set(steps.value.map((s) => s.scriptId).filter(Boolean))]
  await Promise.all(ids.map(async (sid) => {
    if (presetsByScript[sid]) return
    try { presetsByScript[sid] = (await listPresets(sid)) || [] }
    catch { presetsByScript[sid] = [] }
  }))
}

function addStep() {
  steps.value.push({ scriptId: null, presetId: null, continueOnFailure: false })
}
async function removeStep(i) {
  const s = steps.value[i]
  const scriptLabel = scriptNameOf(s?.scriptId)
  try {
    await ElMessageBox.confirm(`确认移除步骤 #${i + 1}「${scriptLabel}」？此操作不会持久化，需点保存才生效。`, '确认', { type: 'warning' })
    steps.value.splice(i, 1)
  } catch (_) { /* cancelled */ }
}
function moveUp(i) { if (i <= 0) return; const a = steps.value[i - 1]; steps.value[i - 1] = steps.value[i]; steps.value[i] = a }
function moveDown(i) { if (i >= steps.value.length - 1) return; const a = steps.value[i + 1]; steps.value[i + 1] = steps.value[i]; steps.value[i] = a }

async function submitForm() {
  if (!form.name) return ElMessage.warning('名称必填')
  if (!steps.value.length) return ElMessage.warning('至少需要一步')
  if (steps.value.some((s) => !s.scriptId)) return ElMessage.warning('每一步都需要选择脚本')
  saving.value = true
  try {
    let id = form.id
    if (id) await updateScenario(id, { ...form })
    else id = (await createScenario({ ...form })).id
    await replaceScenarioSteps(id, steps.value.map((s) => ({
      scriptId: s.scriptId, presetId: s.presetId,
      continueOnFailure: !!s.continueOnFailure
    })))
    ElMessage.success('已保存')
    formOpen.value = false
    await refresh()
  } finally { saving.value = false }
}

async function confirmDelete(row) {
  await ElMessageBox.confirm(`确认删除场景「${row.name}」？`, '确认', { type: 'warning' })
  await deleteScenario(row.id)
  ElMessage.success('已删除')
  await refresh()
}

function openRun(row) {
  runTarget.value = row
  runTenantId.value = null
  runOpen.value = true
}

async function doRun() {
  if (!runTenantId.value) return ElMessage.warning('请选择租户')
  running.value = true
  try {
    runResult.value = await runScenario(runTarget.value.id, runTenantId.value)
    runOpen.value = false
    resultOpen.value = true
  } finally { running.value = false }
}

watch(steps, () => { loadPresetsForSteps() }, { deep: true })

onMounted(async () => {
  const [s, t] = await Promise.all([listScripts().catch(() => []), listTenants().catch(() => [])])
  scripts.value = s || []
  tenants.value = t || []
  await refresh()
})
</script>

<style scoped>
.sb-edit-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 16px;
}
.sb-steps-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  margin-bottom: 8px;
}
.sb-step-row {
  border: 1px solid var(--sb-border);
  border-radius: 4px;
  padding: 10px 12px;
  margin-bottom: 10px;
  background: #fcfcfd;
}
.sb-step-row-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.sb-step-actions { display: flex; gap: 4px; }
.sb-step-idx {
  font-family: var(--sb-mono);
  font-size: 12px;
  color: var(--sb-text-3);
}
.sb-step-form :deep(.el-form-item) { margin-bottom: 10px; }
.sb-status-card {
  padding: 12px 14px;
  border-radius: 6px;
  font-weight: 600;
  margin-bottom: 14px;
  border: 1px solid var(--sb-border);
}
.sb-status-card.ok { background: #f0fdf4; border-color: #bbf7d0; color: var(--sb-success); }
.sb-status-card.fail { background: #fef2f2; border-color: #fecaca; color: var(--sb-danger); }
.sb-status-headline { font-size: 14px; }
.sb-status-sub { font-size: 12px; color: var(--sb-text-3); font-weight: 400; }
.sb-name-main { font-weight: 600; }
</style>