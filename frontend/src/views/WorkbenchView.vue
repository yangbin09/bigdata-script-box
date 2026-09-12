<!--
  WorkbenchView — 唯一的脚本入口（原「执行中心」+「脚本管理」合并）。

  为什么合并：两页回答的是同一个问题（"我要跑哪个脚本"），却各有一个执行入口，
  用户被迫在脑子里维护一张对应表。合并后：
    - 首屏 = 收藏 + 按分类的脚本卡片（卡片本身就是执行入口）
    - 顶部可切到表格视图（管理用：启用/禁用、复制、导入导出、删除）
    - 点卡片 → 原地打开 ScriptExecutionDrawer（不跳页、不重新找脚本）
    - 卡片上的「跑上次」= 用历史里那套参数直接跑，连抽屉都不开

  已删除的概念（见 SIMPLIFICATION.md）：
    - 「快捷操作」(QuickAction)：零有机使用，且与参数方案是同一个东西（脚本+租户+参数），
      需要常用就存成一个方案。
-->
<template>
  <div>
    <div class="sb-header">
      <div>
        <h2 class="sb-page-title">工作台</h2>
        <p class="sb-page-sub">
          共 {{ scripts.length }} 个脚本 · 点卡片直接执行 · 「跑上次」用历史参数一键重跑
        </p>
      </div>
      <div class="sb-head-actions">
        <el-radio-group v-model="mode" size="small">
          <el-radio-button value="cards">卡片</el-radio-button>
          <el-radio-button value="table">表格</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" plain @click="refreshAll" :loading="loading">刷新</el-button>
        <el-upload
          :show-file-list="false"
          :auto-upload="false"
          :on-change="onImportFile"
          accept=".zip"
        >
          <el-button plain :icon="UploadFilled">导入 .zip</el-button>
        </el-upload>
        <el-button v-if="mode === 'table'" type="primary" :icon="Plus" @click="openCreate">新增脚本</el-button>
      </div>
    </div>

    <div class="sb-toolbar">
      <el-input
        v-model="search"
        placeholder="搜索脚本名称 / 描述 / 分类"
        clearable
        :prefix-icon="Search"
        class="sb-search"
      />
      <span v-if="planLabel" class="sb-plan-banner">
        <el-icon><Timer /></el-icon>
        {{ planLabel }}
      </span>
    </div>

    <el-empty
      v-if="!loading && !filteredScripts.length && !search.trim()"
      description="还没有脚本。切到表格视图点「新增脚本」上传 .sh，或从模板创建。"
    />
    <el-empty
      v-else-if="!loading && !filteredScripts.length"
      :description="`没有匹配「${search}」的脚本。`"
    />

    <!-- ============ 卡片视图：执行优先 ============ -->
    <template v-if="mode === 'cards'">
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
                v-if="r.lastStatus"
                size="small" :type="tagTypeOf(r.lastStatus)"
                disable-transitions effect="plain"
              >{{ labelOf(r.lastStatus) }}</el-tag>
            </div>
            <div class="sb-recent-meta">
              <span>{{ r.lastTenantName || '—' }}</span>
              <span class="dot">·</span>
              <span>{{ formatDateTime(r.lastStartTime) }}</span>
              <el-button
                class="sb-recent-run"
                size="small" link type="primary"
                @click.stop="rerunLastById(r.id)"
              >跑上次</el-button>
            </div>
          </div>
        </div>
      </section>

      <!-- 收藏 -->
      <section v-if="favoriteScripts.length" class="sb-block">
        <h3 class="sb-section-title">
          收藏 <span class="count">{{ favoriteScripts.length }}</span>
        </h3>
        <div class="sb-card-grid">
          <ScriptCard
            v-for="s in favoriteScripts"
            :key="`fav-${s.id}`"
            :script="s"
            @click="openDrawer(s)"
            @toggle-favorite="(v) => toggleFavorite(s, v)"
            @rerun="rerunLast(s)"
          />
        </div>
      </section>

      <!-- 按分类 -->
      <section v-for="group in groupedVisible" :key="group.category" class="sb-block">
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
            @rerun="rerunLast(s)"
          />
        </div>
      </section>
    </template>

    <!-- ============ 表格视图：管理优先（原脚本管理页） ============ -->
    <el-table v-else :data="filteredScripts" v-loading="loading" class="sb-card" stripe>
      <template #empty>
        <el-empty description="还没有脚本。" />
      </template>
      <el-table-column label="名称" min-width="200">
        <template #default="{ row }">
          <div class="sb-name-cell">
            <button
              class="sb-star"
              :class="{ active: row.favorite }"
              @click="toggleFavorite(row)"
              :title="row.favorite ? '取消收藏' : '收藏'"
            >
              <el-icon :size="14">
                <component :is="row.favorite ? StarFilled : Star" />
              </el-icon>
            </button>
            <div>
              <div class="sb-name-main">{{ row.displayName || row.name }}</div>
              <div class="sb-name-sub mono">{{ row.name }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="分类" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.category" size="small" disable-transitions>{{ row.category }}</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="风险" width="90">
        <template #default="{ row }">
          <el-tag
            size="small" disable-transitions effect="plain"
            :type="RISK_LEVEL_TAG_TYPE[normalizeRiskLevel(row.riskLevel)]"
          >{{ RISK_LEVEL_LABEL[normalizeRiskLevel(row.riskLevel)] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column label="参数" width="70" align="center">
        <template #default="{ row }">
          <span class="mono">{{ paramCounts[row.id] ?? '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="超时" width="90" align="right">
        <template #default="{ row }">{{ row.timeoutSeconds || 600 }}s</template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.enabled === false ? 'info' : 'success'"
            disable-transitions effect="plain"
          >{{ row.enabled === false ? '已禁用' : '已启用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" :icon="VideoPlay" @click="openRun(row)">执行</el-button>
          <el-button size="small" :icon="RefreshRight" @click="rerunLast(row)">跑上次</el-button>
          <el-button size="small" @click="goEdit(row)">编辑</el-button>
          <el-dropdown trigger="click" @command="(c) => onMore(c, row)">
            <el-button size="small" :icon="MoreFilled" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="copy">复制</el-dropdown-item>
                <el-dropdown-item command="export">导出 .zip</el-dropdown-item>
                <el-dropdown-item command="toggle">
                  {{ row.enabled === false ? '启用' : '禁用' }}
                </el-dropdown-item>
                <el-dropdown-item command="delete" divided>
                  <span style="color: var(--sb-danger)">删除</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <!-- 执行 Drawer：与所有入口共用同一个组件 -->
    <ScriptExecutionDrawer
      ref="execDrawerRef"
      v-model="drawerOpen"
      :script="activeScript"
      :initial-tenant-id="initialTenantId"
      :initial-params="initialParams"
      :auto-run="autoRun"
      allow-batch
      allow-preview
      allow-quick-save
      @preview="openPreview"
      @save-quick="openSavePlan"
      @finished="onExecutionFinished"
      @recent-updated="(r) => { recentList.value = r || [] }"
    />

    <!-- 预览命令 -->
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
        <h4 class="sb-test-section-title">参数</h4>
        <pre class="sb-log">{{ JSON.stringify(previewData.params, null, 2) }}</pre>
        <h4 class="sb-test-section-title">环境变量（敏感已脱敏）</h4>
        <pre class="sb-log">{{ JSON.stringify(previewData.globalVariables, null, 2) }}</pre>
        <div class="sb-preview-meta">
          <div><span class="muted">租户：</span>{{ previewData.tenantName }}</div>
          <div><span class="muted">超时：</span>{{ previewData.timeoutSeconds }} 秒</div>
          <div><span class="muted">kinit 包装：</span>{{ previewData.kinitWrapped ? '是' : '否' }}</div>
        </div>
      </template>
      <template v-else>
        <div v-loading="true" style="height: 80px" />
      </template>
    </el-drawer>

    <!-- 新增脚本 -->
    <el-drawer v-model="createOpen" title="新增脚本" direction="rtl" size="480px">
      <el-form :model="createForm" label-position="top">
        <el-form-item required>
          <template #label><SBLabel text="技术名称" tip="Shell 脚本接收到的参数名前缀（用于路由/API 调用）。" required /></template>
          <el-input v-model="createForm.name" placeholder="小写字母+数字+下划线，例如 daily_etl" />
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="显示名" tip="在执行界面展示的友好名称。留空则回退到技术名称。" /></template>
          <el-input v-model="createForm.displayName" placeholder="留空则使用技术名称" />
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="分类" tip="用于分组显示。常用：Mock、Hudi、Flink、Hive。" /></template>
          <el-input v-model="createForm.category" placeholder="例如：Mock / Hudi / Flink" />
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="描述" tip="简短说明，便于协作者理解用途。" /></template>
          <el-input v-model="createForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="超时时间（秒）" tip="超过该时间后系统会终止进程。" /></template>
          <el-input-number v-model="createForm.timeoutSeconds" :min="1" :max="86400" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="风险等级" tip="只有「危险」会触发二次确认。" /></template>
          <el-select v-model="createForm.riskLevel" style="width: 100%">
            <el-option v-for="o in RISK_LEVEL_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="允许并发执行" tip="默认同一脚本同一租户互斥。" /></template>
          <el-switch v-model="createForm.allowConcurrent" />
        </el-form-item>
        <el-form-item required>
          <template #label><SBLabel text="Shell 文件" tip="拖拽或选择 .sh 文件。" required /></template>
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            accept=".sh"
            drag
          >
            <div class="el-upload__text">拖拽 .sh 到此处或<em>点击选择</em></div>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-drawer>

    <!-- 存为参数方案（取代原「保存为快捷操作」：同一个概念只留一个名字） -->
    <el-dialog v-model="savePlanOpen" title="存为参数方案" width="460px">
      <p class="muted sb-save-hint">
        方案 = 一组可复用的参数（脚本 + 租户 + 参数值）。
        下次执行时可直接选用；不存也可以用「跑上次」复用历史参数。
      </p>
      <el-form label-position="top">
        <el-form-item label="方案名称" required>
          <el-input v-model="savePlanName" placeholder="例如：每日巡检" />
        </el-form-item>
        <el-form-item label="参数预览">
          <pre class="sb-log sb-save-preview">{{ JSON.stringify(savePlanParams, null, 2) }}</pre>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="savePlanOpen = false">取消</el-button>
        <el-button type="primary" :loading="savingPlan" @click="submitSavePlan">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Refresh, Search, VideoPlay, RefreshRight, Close, ArrowRight, ArrowDown,
  Plus, Star, StarFilled, MoreFilled, UploadFilled, Timer
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listScripts, getScript, setScriptFavorite, createScript, deleteScript,
  setScriptEnabled, copyScript, listParams, scriptRelatedCounts
} from '../api/scripts'
import { listTenants } from '../api/tenants'
import { recentScripts, lastPlan } from '../api/history'
import { dryRun, exportScript, importScript, createPreset } from '../api/extras'
import ScriptCard from '../components/ScriptCard.vue'
import ScriptExecutionDrawer from '../components/ScriptExecutionDrawer.vue'
import SBLabel from '../components/SBLabel.vue'
import { formatDateTime } from '../utils/format'
import { takeSessionItem, KEYS } from '../utils/storage'
import {
  RISK_LEVEL_OPTIONS, RISK_LEVEL_LABEL, RISK_LEVEL_TAG_TYPE, normalizeRiskLevel,
  labelOf, tagTypeOf
} from '../utils/labels'

const router = useRouter()
const scripts = ref([])
const tenants = ref([])
const recentList = ref([])
const loading = ref(false)
const mode = ref('cards')
const search = ref('')
const collapsed = reactive({})
const paramCounts = reactive({})

// 执行 Drawer
const execDrawerRef = ref(null)
const drawerOpen = ref(false)
const activeScript = ref(null)
const initialTenantId = ref(null)
const initialParams = ref(null)
const autoRun = ref(false)
const planLabel = ref('')

// 预览
const previewOpen = ref(false)
const previewData = ref(null)

// 新增脚本
const createOpen = ref(false)
const createForm = reactive({
  name: '', displayName: '', category: '', description: '',
  timeoutSeconds: 600, riskLevel: 'READ_ONLY', allowConcurrent: false
})
const scriptFile = ref(null)
const uploadRef = ref(null)
const creating = ref(false)

// 存为方案
const savePlanOpen = ref(false)
const savePlanName = ref('')
const savePlanParams = ref({})
const savePlanTenantId = ref(null)
const savingPlan = ref(false)

const enabledTenants = computed(() => (tenants.value || []).filter((t) => t.enabled !== false))

const filteredScripts = computed(() => {
  const term = search.value.trim().toLowerCase()
  return (scripts.value || []).filter((s) => {
    if (s.enabled === false && mode.value === 'cards') return false
    if (!term) return true
    return (s.displayName || s.name || '').toLowerCase().includes(term)
      || (s.description || '').toLowerCase().includes(term)
      || (s.category || '').toLowerCase().includes(term)
      || (s.name || '').toLowerCase().includes(term)
  })
})

const favoriteScripts = computed(() => filteredScripts.value.filter((s) => s.favorite))

const groupedVisible = computed(() => {
  const favSet = new Set(favoriteScripts.value.map((s) => s.id))
  const map = new Map()
  for (const s of filteredScripts.value) {
    if (favSet.has(s.id)) continue
    const k = s.category || '默认'
    if (!map.has(k)) map.set(k, [])
    map.get(k).push(s)
  }
  const list = [...map.entries()].map(([category, items]) => ({ category, scripts: items }))
  list.sort((a, b) => (a.category === 'Mock' ? 1 : 0) - (b.category === 'Mock' ? 1 : 0))
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

// ======================================================================
// 数据
// ======================================================================

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
  if (mode.value === 'table') {
    for (const k of Object.keys(paramCounts)) delete paramCounts[k]
    await Promise.all(scripts.value.map(async (s) => {
      try {
        const p = await listParams(s.id)
        paramCounts[s.id] = (p || []).length
      } catch { paramCounts[s.id] = 0 }
    }))
  }
}

function onExecutionFinished() {
  recentScripts(6).then((r) => { recentList.value = r || [] }).catch(() => {})
}

// ======================================================================
// 执行入口（全部收敛到同一个 Drawer）
// ======================================================================

function openDrawer(s, { tenantId = null, params = null, run = false } = {}) {
  if (!s) return
  if (s.enabled === false) return ElMessage.warning('脚本已禁用，无法执行')
  initialTenantId.value = tenantId
  initialParams.value = params
  autoRun.value = run
  activeScript.value = s
  drawerOpen.value = true
}

function openRun(row) { openDrawer(row) }

async function openDrawerById(id) {
  const s = scripts.value.find((x) => x.id === id)
  if (!s) return ElMessage.warning('脚本已被删除')
  openDrawer(s)
}

/**
 * 「跑上次」：用历史推导出的参数直接执行，不打开表单。
 *
 * <p>这是把高频路径从「开抽屉 → 填参数 → 提交」压成一次点击的关键：
 * 任何一次成功执行的参数本身就是一套可用方案（见后端 GET /api/history/plan）。
 * 没有成功历史时退回「打开抽屉 + 默认值」，而不是报错。
 */
async function rerunLast(row) {
  if (!row) return
  if (row.enabled === false) return ElMessage.warning('脚本已禁用，无法执行')
  try {
    planLabel.value = '正在查找上次成功的参数…'
    const plan = await lastPlan(row.id, null)
    planLabel.value = ''
    if (!plan?.executionId) {
      ElMessage.info('这个脚本还没有成功记录，已打开表单让你确认参数')
      return openDrawer(row)
    }
    openDrawer(row, { tenantId: plan.tenantId, params: plan.parameters, run: true })
  } catch (e) {
    planLabel.value = ''
    ElMessage.warning(`读取上次参数失败：${e?.message || '未知错误'}，已改为打开表单`)
    openDrawer(row)
  }
}

async function rerunLastById(id) {
  const s = scripts.value.find((x) => x.id === id)
  if (s) await rerunLast(s)
}

// ======================================================================
// 预览 / 存方案
// ======================================================================

async function openPreview() {
  const script = activeScript.value
  if (!script) return
  previewData.value = null
  previewOpen.value = true
  try {
    const snap = execDrawerRef.value?.snapshot?.() || {}
    previewData.value = await dryRun({
      scriptId: script.id,
      tenantId: snap.tenantId,
      params: snap.params || {},
      presetId: snap.presetId ?? null
    })
  } catch {
    previewOpen.value = false
  }
}

function openSavePlan() {
  const snap = execDrawerRef.value?.snapshot?.() || {}
  savePlanName.value = ''
  savePlanParams.value = snap.params || {}
  savePlanTenantId.value = snap.tenantId || null
  savePlanOpen.value = true
}

async function submitSavePlan() {
  const name = savePlanName.value.trim()
  if (!name) return ElMessage.warning('方案名称必填')
  if (!activeScript.value) return
  savingPlan.value = true
  try {
    await createPreset(activeScript.value.id, {
      name,
      paramsJson: JSON.stringify(savePlanParams.value),
      tenantId: savePlanTenantId.value
    })
    ElMessage.success(`已保存方案「${name}」`)
    savePlanOpen.value = false
  } catch (_) { /* interceptor surfaced */ } finally {
    savingPlan.value = false
  }
}

// ======================================================================
// 脚本管理（表格视图）
// ======================================================================

function openCreate() {
  Object.assign(createForm, {
    name: '', displayName: '', category: '', description: '',
    timeoutSeconds: 600, riskLevel: 'READ_ONLY', allowConcurrent: false
  })
  scriptFile.value = null
  uploadRef.value?.clearFiles?.()
  createOpen.value = true
}

function onFileChange(file) { scriptFile.value = file.raw }

async function submitCreate() {
  if (!createForm.name) return ElMessage.warning('名称必填')
  if (!scriptFile.value) return ElMessage.warning('请上传 .sh 文件')
  if (!scriptFile.value.name.toLowerCase().endsWith('.sh')) return ElMessage.warning('只接受 .sh 文件')
  creating.value = true
  try {
    const fd = new FormData()
    fd.append('name', createForm.name)
    if (createForm.displayName) fd.append('displayName', createForm.displayName)
    if (createForm.category) fd.append('category', createForm.category)
    if (createForm.description) fd.append('description', createForm.description)
    fd.append('timeoutSeconds', String(createForm.timeoutSeconds))
    fd.append('enabled', 'true')
    fd.append('riskLevel', createForm.riskLevel || 'READ_ONLY')
    fd.append('allowConcurrent', String(!!createForm.allowConcurrent))
    fd.append('file', scriptFile.value)
    await createScript(fd)
    ElMessage.success('已创建')
    createOpen.value = false
    await refreshAll()
  } finally { creating.value = false }
}

async function toggleFavorite(row, val) {
  const v = typeof val === 'boolean' ? val : !row.favorite
  await setScriptFavorite(row.id, v)
  row.favorite = v
}

async function toggleEnabled(row) {
  const v = row.enabled === false
  await setScriptEnabled(row.id, v)
  row.enabled = v
  ElMessage.success(v ? '已启用' : '已禁用')
}

async function onMore(cmd, row) {
  switch (cmd) {
    case 'copy': {
      const copy = await copyScript(row.id)
      ElMessage.success(`已复制为「${copy.displayName}」（已禁用）`)
      await refreshAll()
      break
    }
    case 'export': await doExport(row); break
    case 'toggle': await toggleEnabled(row); break
    case 'delete': await confirmDelete(row); break
  }
}

async function doExport(row) {
  try {
    const blob = await exportScript(row.id)
    const url = URL.createObjectURL(new Blob([blob], { type: 'application/zip' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `${row.name}-${row.id}.zip`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    setTimeout(() => URL.revokeObjectURL(url), 0)
    ElMessage.success('已导出')
  } catch (_) { /* surfaced */ }
}

async function onImportFile(file) {
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.zip')) return ElMessage.error('只接受 .zip 包')
  try {
    const res = await importScript(file.raw || file)
    ElMessage.success(`已导入「${res.name}」`)
    await refreshAll()
  } catch (_) { /* surfaced */ }
}

async function confirmDelete(row) {
  let counts = { historyCount: 0, presetCount: 0, scenarioCount: 0 }
  try {
    counts = (await scriptRelatedCounts(row.id)) || counts
  } catch (_) { /* tolerate */ }
  const parts = []
  if (counts.historyCount) parts.push(`${counts.historyCount} 条执行历史`)
  if (counts.presetCount) parts.push(`${counts.presetCount} 个参数方案`)
  if (counts.scenarioCount) parts.push(`${counts.scenarioCount} 个场景步骤引用`)
  const related = parts.length ? `\n将关联影响：${parts.join('、')}` : ''
  const ok = await ElMessageBox.confirm(
    `确认删除脚本「${row.displayName || row.name}」？此操作不可恢复。${related}`,
    '确认', { type: 'warning' }).catch(() => null)
  if (!ok) return
  await deleteScript(row.id)
  ElMessage.success('已删除')
  await refreshAll()
}

function goEdit(row) { router.push({ name: 'script-edit', query: { id: row.id } }) }

// 消费历史页放的一次性重跑请求
async function consumeRerun() {
  const payload = takeSessionItem(KEYS.RERUN)
  if (!payload?.scriptId) return
  const s = scripts.value.find((x) => x.id === payload.scriptId)
  if (!s) return ElMessage.warning('原脚本已不存在，无法重跑')
  if (s.enabled === false) return ElMessage.warning('脚本已禁用，无法重跑')
  const tenantId = payload.tenantId
    && enabledTenants.value.some((t) => t.id === payload.tenantId) ? payload.tenantId : null
  // autoRun=true 表示「历史页点了跑上次」——不再让用户点第二次
  openDrawer(s, { tenantId, params: payload.params || null, run: !!payload.autoRun })
}

onMounted(async () => {
  await refreshAll()
  await consumeRerun()
})
</script>

<style scoped>
.sb-head-actions { display: flex; align-items: center; gap: 8px; }
.sb-toolbar { margin-bottom: 16px; display: flex; align-items: center; gap: 12px; }
.sb-search { max-width: 420px; }
.sb-plan-banner {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px; color: var(--sb-text-3);
}
.sb-block { margin-bottom: 24px; }
.sb-group-title { cursor: pointer; user-select: none; padding: 2px 0; }
.sb-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}
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
.sb-recent-meta {
  display: flex; align-items: center; gap: 6px;
  font-size: 12px; color: var(--sb-text-3);
}
.sb-recent-run { margin-left: auto; }

.sb-name-cell { display: flex; align-items: flex-start; gap: 8px; }
.sb-name-main { font-weight: 600; font-size: 13.5px; }
.sb-name-sub { font-size: 11.5px; color: var(--sb-text-3); }
.sb-star {
  background: transparent; border: none; cursor: pointer;
  padding: 2px; border-radius: 4px; color: var(--sb-text-3);
  display: flex; align-items: center; justify-content: center; margin-top: 2px;
}
.sb-star.active { color: #f59e0b; }

.sb-save-hint { margin: 0 0 12px; font-size: 12.5px; line-height: 1.6; }
.sb-save-preview { max-height: 180px; overflow: auto; }
</style>
