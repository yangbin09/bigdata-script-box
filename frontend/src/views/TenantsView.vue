<!--
  TenantsView — table of tenants + create/edit drawer + keytab upload + test modal.
-->
<template>
  <div>
    <div class="sb-header">
      <div>
        <h2 class="sb-page-title">租户管理</h2>
        <p class="sb-page-sub">Kerberos 主体（principal）及其 keytab 绑定。Mock 模式下不实际执行 kinit。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增租户</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" class="sb-card" stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="名称" min-width="120">
        <template #default="{ row }">
          <span class="mono">{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="principal" label="principal" min-width="200">
        <template #default="{ row }">
          <span class="mono">{{ row.principal }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="defaultDatabase" label="默认数据库" min-width="120" />
      <el-table-column prop="keytabPath" label="keytab" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.keytabPath" class="mono">{{ row.keytabPath }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
      <el-table-column prop="enabled" label="启用" width="80" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            @change="(v) => toggleEnabled(row, v)"
            inline-prompt
            active-text="on"
            inactive-text="off"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" @click="openKeytab(row)">keytab</el-button>
          <el-button size="small" type="primary" plain @click="runTest(row)">测试</el-button>
          <el-button size="small" type="danger" plain @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Create / Edit Drawer -->
    <el-drawer
      v-model="formOpen"
      :title="form.id ? '编辑租户' : '新增租户'"
      direction="rtl"
      size="440px"
    >
      <el-form :model="form" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 mock-hive" />
        </el-form-item>
        <el-form-item label="principal" required>
          <el-input v-model="form.principal" placeholder="hive@EXAMPLE.COM" />
        </el-form-item>
        <el-form-item label="默认数据库">
          <el-input v-model="form.defaultDatabase" placeholder="default" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-drawer>

    <!-- Keytab upload dialog -->
    <el-dialog v-model="keytabOpen" :title="`上传 keytab — ${keytabTarget?.name || ''}`" width="440px">
      <el-upload
        ref="keytabUploadRef"
        :auto-upload="false"
        :limit="1"
        :on-change="onKeytabFile"
        accept=".keytab"
        drag
      >
        <div class="el-upload__text">拖拽 .keytab 到此处或<em>点击选择</em></div>
      </el-upload>
      <template #footer>
        <el-button @click="keytabOpen = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitKeytab">上传</el-button>
      </template>
    </el-dialog>

    <!-- Test result dialog -->
    <el-dialog v-model="testOpen" title="租户测试结果" width="640px">
      <div v-if="testResult">
        <div class="sb-test-summary" :class="{ ok: testResult.ok, fail: !testResult.ok }">
          <el-icon :size="20">
            <component :is="testResult.ok ? CircleCheck : CircleClose" />
          </el-icon>
          <span>{{ testResult.ok ? 'OK' : 'FAILED' }}</span>
          <span v-if="testResult.mode" class="muted">mode={{ testResult.mode }}</span>
          <span v-if="testResult.kinitExit != null" class="muted">kinit exit={{ testResult.kinitExit }}</span>
        </div>
        <pre v-if="testResult.stdout" class="sb-log">{{ testResult.stdout }}</pre>
        <div v-if="testResult.klist" class="sb-test-section">
          <div class="sb-test-section-title">klist</div>
          <pre class="sb-log">{{ testResult.klist }}</pre>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Plus, CircleCheck, CircleClose } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listTenants, createTenant, updateTenant, deleteTenant,
  setTenantEnabled, uploadKeytab, testTenant
} from '../api/tenants'

const rows = ref([])
const loading = ref(false)

const formOpen = ref(false)
const form = reactive({
  id: null, name: '', principal: '', defaultDatabase: '',
  description: '', enabled: true
})
const saving = ref(false)

const keytabOpen = ref(false)
const keytabTarget = ref(null)
const keytabFile = ref(null)
const keytabUploadRef = ref(null)
const uploading = ref(false)

const testOpen = ref(false)
const testResult = ref(null)

async function refresh() {
  loading.value = true
  try { rows.value = (await listTenants()) || [] }
  finally { loading.value = false }
}

function openCreate() {
  Object.assign(form, {
    id: null, name: '', principal: '', defaultDatabase: '',
    description: '', enabled: true
  })
  formOpen.value = true
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id, name: row.name, principal: row.principal,
    defaultDatabase: row.defaultDatabase, description: row.description,
    enabled: row.enabled
  })
  formOpen.value = true
}

async function submitForm() {
  if (!form.name || !form.principal) return ElMessage.warning('名称和 principal 必填')
  saving.value = true
  try {
    const payload = { ...form }
    delete payload.id
    if (form.id) await updateTenant(form.id, payload)
    else         await createTenant(payload)
    ElMessage.success('已保存')
    formOpen.value = false
    await refresh()
  } finally { saving.value = false }
}

async function toggleEnabled(row, val) {
  await setTenantEnabled(row.id, val)
  row.enabled = val
}

async function confirmDelete(row) {
  await ElMessageBox.confirm(`确认删除租户「${row.name}」？`, '确认', { type: 'warning' })
  await deleteTenant(row.id)
  ElMessage.success('已删除')
  await refresh()
}

function openKeytab(row) {
  keytabTarget.value = row
  keytabFile.value = null
  keytabUploadRef.value?.clearFiles?.()
  keytabOpen.value = true
}
function onKeytabFile(file) { keytabFile.value = file.raw }

async function submitKeytab() {
  if (!keytabFile.value) return ElMessage.warning('请选择文件')
  if (!keytabFile.value.name.toLowerCase().endsWith('.keytab'))
    return ElMessage.warning('只接受 .keytab 文件')
  uploading.value = true
  try {
    await uploadKeytab(keytabTarget.value.id, keytabFile.value)
    ElMessage.success('已上传')
    keytabOpen.value = false
    await refresh()
  } finally { uploading.value = false }
}

async function runTest(row) {
  testOpen.value = true
  testResult.value = null
  try {
    const r = await testTenant(row.id)
    testResult.value = r
  } catch {
    testOpen.value = false
  }
}

onMounted(refresh)
</script>

<style scoped>
.sb-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 16px;
}
.sb-page-title { margin: 0; font-size: 18px; font-weight: 600; }
.sb-page-sub { margin: 4px 0 0 0; color: var(--sb-text-3); font-size: 13px; }

.mono { font-family: var(--sb-mono); font-size: 13px; }
.muted { color: var(--sb-text-3); }

.sb-test-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 4px;
  background: #f3f4f6;
  margin-bottom: 10px;
  font-weight: 600;
}
.sb-test-summary.ok   { background: #f0fdf4; color: var(--sb-success); }
.sb-test-summary.fail { background: #fef2f2; color: var(--sb-danger); }

.sb-test-section { margin-top: 10px; }
.sb-test-section-title { font-size: 12px; color: var(--sb-text-3); margin-bottom: 4px; }
</style>