<!--
  TenantsView — tenant table + create/edit drawer + keytab upload + test drawer.

  Visual improvements:
    - keytab column shows ✓ 已配置 / 未配置 (full path on hover via tooltip)
    - Test result is shown in a dedicated drawer with clearer status pills.
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
      <template #empty>
        <el-empty description="还没有租户。点击右上角「新增租户」开始。" />
      </template>
      <el-table-column label="编号" width="60" prop="id" />
      <el-table-column label="名称" min-width="140">
        <template #default="{ row }">
          <div class="sb-name-main">{{ row.name }}</div>
        </template>
      </el-table-column>
      <el-table-column label="Principal（Kerberos 主体）" min-width="220">
        <template #default="{ row }">
          <span class="mono">{{ row.principal }}</span>
        </template>
      </el-table-column>
      <el-table-column label="默认数据库" min-width="120" prop="defaultDatabase" />
      <el-table-column label="Keytab 文件" min-width="120">
        <template #default="{ row }">
          <el-tooltip
            v-if="row.keytabPath"
            :content="row.keytabPath"
            placement="top"
          >
            <span class="sb-kt-configured">✓ 已配置</span>
          </el-tooltip>
          <span v-else class="muted">未配置</span>
        </template>
      </el-table-column>
      <el-table-column label="描述" min-width="180" prop="description" show-overflow-tooltip />
      <el-table-column label="状态" width="90" align="center">
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
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" @click="openKeytab(row)">keytab</el-button>
          <el-button size="small" type="primary" plain @click="runTest(row)">测试</el-button>
          <el-button size="small" type="danger" plain @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Create / Edit Drawer -->
    <el-drawer v-model="formOpen" :title="form.id ? '编辑租户' : '新增租户'" direction="rtl" size="440px">
      <el-form :model="form" label-position="top">
        <el-form-item required>
          <template #label><SBLabel text="名称" tip="显示给用户的友好名称。建议简短、语义清晰，例如 mock-hive、prod-hive。" required /></template>
          <el-input v-model="form.name" placeholder="例如：mock-hive" />
        </el-form-item>
        <el-form-item required>
          <template #label><SBLabel text="Principal（Kerberos 主体）" tip="Kerberos 主体名，格式为 user/instance@REALM。例如 hive@EXAMPLE.COM。执行脚本时会用此 principal 进行 kinit 认证。" required /></template>
          <el-input v-model="form.principal" placeholder="hive@EXAMPLE.COM" />
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="默认数据库" tip="脚本执行时使用的默认 Hive/Spark 数据库。仅作提示，实际脚本里仍可自由切换。" /></template>
          <el-input v-model="form.defaultDatabase" placeholder="default" />
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="描述" tip="对该租户的简短说明，便于协作者区分环境或业务范围。" /></template>
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="例如：开发环境 Hive 租户" />
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="启用" tip="关闭后此租户不会出现在执行页面的租户下拉列表中。" /></template>
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-drawer>

    <!-- Keytab upload dialog -->
    <el-dialog v-model="keytabOpen" :title="`上传 Keytab 文件 — ${keytabTarget?.name || ''}`" width="440px">
      <p class="sb-help" style="margin-top: 0">
        上传与租户 Principal 匹配的 Keytab 文件。Keytab 通常小于 1 KB，过大的文件将被拒绝。
      </p>
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

    <!-- Test result drawer -->
    <el-drawer v-model="testOpen" direction="rtl" size="520px" :show-close="false" class="sb-exec-drawer">
      <template #header>
        <div class="sb-drawer-header">
          <div>
            <div class="sb-drawer-title">租户测试 — {{ testTarget?.name || '' }}</div>
            <div v-if="testTarget" class="sb-exec-sub">
              <span>{{ testTarget.principal }}</span>
            </div>
          </div>
          <el-button text :icon="Close" @click="testOpen = false" />
        </div>
      </template>

      <template v-if="testResult">
        <div class="sb-test-card" :class="{ ok: testResult.ok, fail: !testResult.ok }">
          <el-icon :size="22">
            <component :is="testResult.ok ? CircleCheck : CircleClose" />
          </el-icon>
          <span>{{ testResult.ok ? '通过' : '失败' }}</span>
          <span v-if="testResult.mode" class="muted">· 环境 {{ testResult.mode }}</span>
          <span v-if="testResult.kinitExit != null" class="muted">· kinit exit={{ testResult.kinitExit }}</span>
        </div>

        <h4 class="sb-test-section-title">Kerberos</h4>
        <div class="sb-test-row">
          <span class="dot-green">●</span><span>kinit</span>
          <span class="status">{{ testResult.kinitExit == null ? (testResult.ok ? '成功' : '失败') : (testResult.kinitExit === 0 ? '成功' : `exit ${testResult.kinitExit}`) }}</span>
        </div>
        <div class="sb-test-row">
          <span class="dot-green">●</span><span>principal</span>
          <span class="mono">{{ testTarget?.principal }}</span>
        </div>
        <div v-if="testResult.klist != null" class="sb-test-row">
          <span class="dot-green">●</span><span>klist</span>
          <span class="status">{{ testResult.ok ? '正常' : '异常' }}</span>
        </div>
        <div class="sb-test-row">
          <span class="dot-green">●</span><span>环境</span>
          <span class="status">{{ testResult.mode === 'mock' ? 'Mock' : 'Real' }}</span>
        </div>

        <pre v-if="testResult.stdout" class="sb-log">{{ testResult.stdout }}</pre>
        <pre v-if="testResult.klist" class="sb-log">{{ testResult.klist }}</pre>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Plus, CircleCheck, CircleClose, Close } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listTenants, createTenant, updateTenant, deleteTenant,
  setTenantEnabled, uploadKeytab, testTenant,
  tenantRelatedCounts
} from '../api/tenants'
import SBLabel from '../components/SBLabel.vue'

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
const testTarget = ref(null)
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
  let counts = { historyCount: 0 }
  try {
    const r = await tenantRelatedCounts(row.id)
    counts = r?.data || counts
  } catch (_) { /* tolerate */ }
  const related = counts.historyCount
    ? `\n将关联影响：${counts.historyCount} 条执行历史（删除后这些记录的引用将悬空）`
    : ''
  await ElMessageBox.confirm(
    `确认删除租户「${row.name}」？${related}`,
    '确认', { type: 'warning' })
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
  // Keytabs are tiny (usually < 1 KB). Reject anything larger than 1 MB so a
  // mis-dropped binary doesn't silently sit on the server.
  if (keytabFile.value.size > 1024 * 1024) {
    return ElMessage.warning(`keytab 文件过大（${(keytabFile.value.size / 1024).toFixed(1)} KB > 1 MB），请确认这是正确的文件`)
  }
  uploading.value = true
  try {
    await uploadKeytab(keytabTarget.value.id, keytabFile.value)
    ElMessage.success('已上传')
    keytabOpen.value = false
    await refresh()
  } finally { uploading.value = false }
}

async function runTest(row) {
  testTarget.value = row
  testResult.value = null
  testOpen.value = true
  try { testResult.value = await testTenant(row.id) }
  catch { /* keep open */ }
}

onMounted(refresh)
</script>

<style scoped>
.sb-name-main { font-weight: 600; }
.sb-kt-configured {
  color: var(--sb-success);
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.sb-test-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-radius: 6px;
  font-weight: 600;
  margin-bottom: 14px;
  border: 1px solid var(--sb-border);
  background: #f8fafc;
}
.sb-test-card.ok   { background: #f0fdf4; border-color: #bbf7d0; color: var(--sb-success); }
.sb-test-card.fail { background: #fef2f2; border-color: #fecaca; color: var(--sb-danger); }

.sb-test-section-title {
  margin: 12px 0 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--sb-text-2);
}

.sb-test-row {
  display: grid;
  grid-template-columns: 16px 100px 1fr;
  gap: 8px;
  align-items: center;
  padding: 6px 0;
  border-bottom: 1px dashed var(--sb-border);
  font-size: 13px;
}
.sb-test-row:last-of-type { border-bottom: 0; }
.sb-test-row .dot-green { color: var(--sb-success); }
.sb-test-row .status {
  color: var(--sb-text-2);
  font-weight: 500;
}
</style>