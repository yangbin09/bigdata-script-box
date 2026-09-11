<!--
  SettingsView — global variables management and other operational toggles.
  Per spec: V1.5 global variable center lives here.
-->
<template>
  <div>
    <div class="sb-header">
      <div>
        <h2 class="sb-page-title">设置</h2>
        <p class="sb-page-sub">全局变量中心：执行时注入到脚本的环境变量（key=value）。敏感字段不会出现在日志或前端明文。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增变量</el-button>
    </div>

    <!-- V2: auto-cleanup panel — retention settings + preview/apply. -->
    <div class="sb-card sb-cleanup">
      <div class="sb-cleanup-head">
        <div>
          <h3 class="sb-section-title">自动清理 (Cleanup)</h3>
          <p class="muted">
            每天 03:00 自动执行。下方四个数字是保留天数（0 = 禁用该类目）。修改后立即生效。
          </p>
        </div>
        <el-tag v-if="cleanupPreview?.historySkippedRunning" type="warning" effect="plain" disable-transitions>
          {{ cleanupPreview.historySkippedRunning }} 个运行中的执行被跳过
        </el-tag>
      </div>

      <div class="sb-cleanup-grid">
        <div class="sb-cleanup-field">
          <label>执行历史 (historyDays)</label>
          <el-input-number v-model="retention.historyDays" :min="0" :max="365" />
        </div>
        <div class="sb-cleanup-field">
          <label>产物文件 (artifactDays)</label>
          <el-input-number v-model="retention.artifactDays" :min="0" :max="365" />
        </div>
        <div class="sb-cleanup-field">
          <label>执行目录 (executionDays)</label>
          <el-input-number v-model="retention.executionDays" :min="0" :max="365" />
        </div>
        <div class="sb-cleanup-field">
          <label>日志 (logDays)</label>
          <el-input-number v-model="retention.logDays" :min="0" :max="365" />
        </div>
      </div>

      <div class="sb-cleanup-actions">
        <el-button :icon="View" :loading="previewLoading" @click="refreshPreview">预览</el-button>
        <el-button type="danger" :icon="Delete" :loading="applyLoading" :disabled="!previewChecked" @click="applyCleanupNow">
          立即执行
        </el-button>
        <el-checkbox v-model="previewChecked">我已确认预览结果</el-checkbox>
        <span v-if="cleanupPreview" class="muted">
          将删除 历史 {{ cleanupPreview.historyCandidates }} 条 · 产物 {{ cleanupPreview.artifactCandidates }} 个 · 目录 {{ cleanupPreview.executionDirsCandidates }} 个
        </span>
      </div>
    </div>

    <el-table :data="rows" v-loading="loading" class="sb-card" stripe>
      <el-table-column label="key" min-width="180">
        <template #default="{ row }">
          <span class="mono">{{ row.variableKey }}</span>
        </template>
      </el-table-column>
      <el-table-column label="value" min-width="220">
        <template #default="{ row }">
          <span class="mono" v-if="!row.sensitive">{{ row.variableValue }}</span>
          <span v-else class="mono sb-masked">******</span>
        </template>
      </el-table-column>
      <el-table-column label="敏感" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.sensitive" size="small" type="warning" disable-transitions effect="plain">是</el-tag>
          <span v-else class="muted">否</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80" align="center">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.enabled === false ? 'info' : 'success'"
            disable-transitions
            effect="plain"
          >{{ row.enabled === false ? '否' : '是' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="描述" min-width="200" prop="description" show-overflow-tooltip />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" plain @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="formOpen" :title="form.id ? '编辑变量' : '新增变量'" direction="rtl" size="420px">
      <el-form :model="form" label-position="top">
        <el-form-item label="key" required>
          <el-input v-model="form.variableKey" placeholder="大写字母、数字、下划线（[A-Za-z_][A-Za-z0-9_]*）" />
        </el-form-item>
        <el-form-item label="value">
          <el-input
            v-model="form.variableValue"
            :type="form.sensitive ? 'password' : 'text'"
            :placeholder="form.sensitive ? '敏感值不会出现在日志或前端明文' : '变量值'"
            show-password
          />
        </el-form-item>
        <el-form-item label="敏感">
          <el-switch v-model="form.sensitive" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Plus, Delete, View } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listGlobalVariables, createGlobalVariable,
  updateGlobalVariable, deleteGlobalVariable
} from '../api/extras'
import {
  previewCleanup, applyCleanup, getSettings, updateSettings
} from '../api/admin'

const rows = ref([])
const loading = ref(false)
const formOpen = ref(false)
const form = reactive({
  id: null, variableKey: '', variableValue: '',
  sensitive: false, enabled: true, description: ''
})
const saving = ref(false)

async function refresh() {
  loading.value = true
  try { rows.value = (await listGlobalVariables()) || [] }
  finally { loading.value = false }
}

function openCreate() {
  Object.assign(form, {
    id: null, variableKey: '', variableValue: '',
    sensitive: false, enabled: true, description: ''
  })
  formOpen.value = true
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id, variableKey: row.variableKey,
    variableValue: row.variableValue || '',
    sensitive: !!row.sensitive, enabled: row.enabled !== false,
    description: row.description || ''
  })
  formOpen.value = true
}

async function submitForm() {
  if (!form.variableKey) return ElMessage.warning('key 必填')
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(form.variableKey))
    return ElMessage.warning('key 必须匹配 [A-Za-z_][A-Za-z0-9_]*')
  saving.value = true
  try {
    const payload = { ...form }
    delete payload.id
    if (form.id) await updateGlobalVariable(form.id, payload)
    else         await createGlobalVariable(payload)
    ElMessage.success('已保存')
    formOpen.value = false
    await refresh()
  } finally { saving.value = false }
}

async function confirmDelete(row) {
  await ElMessageBox.confirm(`确认删除变量「${row.variableKey}」？`, '确认', { type: 'warning' })
  await deleteGlobalVariable(row.id)
  ElMessage.success('已删除')
  await refresh()
}

onMounted(refresh)
onMounted(refreshCleanup)

// V2: cleanup panel state. Retention inputs are bound to a local copy;
// on save they're pushed to /admin/settings. Preview runs on demand and
// gates the destructive apply button with a confirmation checkbox.
const retention = reactive({ historyDays: 30, artifactDays: 30, executionDays: 30, logDays: 7 })
const cleanupPreview = ref(null)
const previewLoading = ref(false)
const previewChecked = ref(false)
const applyLoading = ref(false)

async function refreshCleanup() {
  try {
    const raw = await getSettings()
    const map = raw?.data || raw || {}
    if (typeof map['cleanup.historyDays'] !== 'undefined') retention.historyDays = parseInt(map['cleanup.historyDays'], 10)
    if (typeof map['cleanup.artifactDays'] !== 'undefined') retention.artifactDays = parseInt(map['cleanup.artifactDays'], 10)
    if (typeof map['cleanup.executionDays'] !== 'undefined') retention.executionDays = parseInt(map['cleanup.executionDays'], 10)
    if (typeof map['cleanup.logDays'] !== 'undefined') retention.logDays = parseInt(map['cleanup.logDays'], 10)
  } catch (_) {
    // tolerate 404 / no rows yet — fall back to defaults.
  }
}

async function persistRetention() {
  await updateSettings({
    'cleanup.historyDays': String(retention.historyDays),
    'cleanup.artifactDays': String(retention.artifactDays),
    'cleanup.executionDays': String(retention.executionDays),
    'cleanup.logDays': String(retention.logDays)
  })
}

async function refreshPreview() {
  await persistRetention()
  previewLoading.value = true
  try {
    const r = await previewCleanup()
    cleanupPreview.value = r?.data || r || null
    previewChecked.value = false
  } catch (e) {
    ElMessage.error('预览失败: ' + (e?.message || e))
  } finally {
    previewLoading.value = false
  }
}

async function applyCleanupNow() {
  if (!previewChecked.value) return
  applyLoading.value = true
  try {
    const r = await applyCleanup()
    const data = r?.data || r || {}
    ElMessage.success(
      `已删除: 历史 ${data.historyDeleted} 条, 产物 ${data.artifactsDeleted} 个, 目录 ${data.executionDirsDeleted} 个, 跳过运行中 ${data.skippedRunning} 个`
    )
    previewChecked.value = false
    cleanupPreview.value = null
    await refresh()
  } catch (e) {
    ElMessage.error('清理失败: ' + (e?.message || e))
  } finally {
    applyLoading.value = false
  }
}
</script>

<style scoped>
.sb-masked { color: var(--sb-text-3); }
.mono { font-family: var(--sb-mono); font-size: 12.5px; }
.sb-cleanup {
  padding: 16px 18px;
  margin-bottom: 16px;
}
.sb-cleanup-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
  gap: 12px;
}
.sb-cleanup-head p { margin: 4px 0 0 0; }
.sb-cleanup-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
  margin-bottom: 12px;
}
.sb-cleanup-field { display: flex; flex-direction: column; gap: 4px; }
.sb-cleanup-field label { font-size: 12.5px; color: var(--sb-text-2); }
.sb-cleanup-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  border-top: 1px dashed var(--sb-border);
  padding-top: 12px;
}
.sb-section-title { margin: 0; font-size: 14px; font-weight: 600; }
.muted { color: var(--sb-text-muted); font-size: 12.5px; }
</style>