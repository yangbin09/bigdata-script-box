<!--
  SettingsView — global variables management and Data Cleanup.
  Two tabs: 全局变量 / 数据清理. Each tab is a focused work surface.
-->
<template>
  <div>
    <div class="sb-header">
      <div>
        <h2 class="sb-page-title">设置</h2>
        <p class="sb-page-sub">全局变量中心 + 数据清理。敏感字段不会出现在日志或前端明文。</p>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="settings-tabs">
      <el-tab-pane label="全局变量" name="vars">
        <div class="tab-actions">
          <el-button type="primary" :icon="Plus" @click="openCreate">新增变量</el-button>
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
      </el-tab-pane>

      <el-tab-pane label="数据清理" name="cleanup">
        <CleanupPanel />
      </el-tab-pane>
    </el-tabs>

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
import { onMounted, reactive, ref, watch } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listGlobalVariables, createGlobalVariable,
  updateGlobalVariable, deleteGlobalVariable
} from '../api/extras'
import { getItem, setItem } from '../utils/storage'
import CleanupPanel from '../components/CleanupPanel.vue'

// Persist the active tab across page navigations — users that came from
// a cleanup run land here often and shouldn't have to re-pick the tab.
const SETTINGS_TAB_KEY = 'sb.settingsTab'
const activeTab = ref(getItem(SETTINGS_TAB_KEY, 'vars'))
watch(activeTab, (v) => setItem(SETTINGS_TAB_KEY, v))

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
</script>

<style scoped>
.settings-tabs { background: transparent; }
.tab-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}
.sb-masked { color: var(--sb-text-3); }
.mono { font-family: var(--sb-mono); font-size: 12.5px; }
.muted { color: var(--sb-text-muted); font-size: 12.5px; }
</style>