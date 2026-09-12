<!--
  TenantsView — V3 (PR-1): single drawer for config + keytab + auth test.

  Three previously-separate floats (form / keytab / test) now live in one
  drawer with three zones:
    - 基础信息: name, authName, principal, defaultDatabase, description, enabled
    - Keytab:    drag-drop upload zone
    - 测试结果:  fixed area showing the most recent test result + time

  Footer offers two save buttons:
    - 保存              : write metadata (and keytab if selected) only
    - 保存并测试认证    : same, then run kinit + record lastTestAt/lastTestOk

  When principal / authName / keytab changes, the UI calls
  POST /tenants/{id}/mark-stale to clear lastTestAt, and shows the
  "待重新测试" banner.

  Creation flow: submit without id → server returns row with id → store id
  on the form so a re-submit (e.g. retrying the keytab upload) takes the
  update path. This keeps authName UNIQUE race-free in practice.
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
      <el-table-column label="Auth 名称" min-width="140">
        <template #default="{ row }">
          <span class="mono">{{ row.authName || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="Principal" min-width="200">
        <template #default="{ row }">
          <span class="mono">{{ row.principal }}</span>
        </template>
      </el-table-column>
      <el-table-column label="Keytab 文件" min-width="100">
        <template #default="{ row }">
          <el-tooltip v-if="row.keytabPath" :content="row.keytabPath" placement="top">
            <span class="sb-kt-configured">✓ 已配置</span>
          </el-tooltip>
          <span v-else class="muted">未配置</span>
        </template>
      </el-table-column>
      <el-table-column label="最近测试" min-width="160">
        <template #default="{ row }">
          <span v-if="!row.lastTestAt" class="muted">—</span>
          <span v-else>
            <el-tag :type="row.lastTestOk ? 'success' : 'danger'" size="small" effect="light">
              {{ row.lastTestOk ? '通过' : '失败' }}
            </el-tag>
            <span class="muted" style="margin-left: 6px; font-size: 12px">
              {{ formatDate(row.lastTestAt) }}
            </span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled !== false"
            size="small"
            inline-prompt
            active-text="启用"
            inactive-text="禁用"
            @update:model-value="(v) => toggleEnabled(row, v)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" plain @click="openEdit(row)">编辑 / 测试</el-button>
          <el-button size="small" type="danger" plain @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- V3 (PR-1): unified drawer -->
    <el-drawer
      v-model="drawerOpen"
      :title="form.id ? `编辑租户 #${form.id}` : '新增租户'"
      direction="rtl"
      size="520px"
      :show-close="false"
      :destroy-on-close="false"
      class="sb-tenant-drawer"
    >
      <template #header>
        <div class="sb-drawer-header">
          <div>
            <div class="sb-drawer-title">
              {{ form.id ? `编辑租户 #${form.id}` : '新增租户' }}
            </div>
            <div v-if="form.id" class="sb-exec-sub">
              <span>{{ form.principal || '—' }}</span>
            </div>
          </div>
          <el-button text :icon="Close" @click="drawerOpen = false" />
        </div>
      </template>

      <!-- "待重新测试" banner when principal/keytab/authName changed -->
      <el-alert
        v-if="form.id && needsRetest"
        type="warning"
        :closable="false"
        show-icon
        title="Principal / Auth 名称 / Keytab 已变更，请重新测试认证"
        description="本次保存后会自动清除测试状态，点击「保存并测试认证」可立即重测。"
        style="margin-bottom: 14px"
      />

      <!-- Zone 1: 基础信息 -->
      <section class="sb-zone">
        <h4 class="sb-zone-title">基础信息</h4>
        <el-form :model="form" label-position="top">
          <el-form-item required>
            <template #label><SBLabel text="名称" tip="显示给用户的友好名称。" required /></template>
            <el-input v-model="form.name" placeholder="例如：mock-hive" />
          </el-form-item>
          <el-form-item required>
            <template #label><SBLabel text="Auth 名称（Principal 别名）" tip="与 Principal 一一对应，全局唯一。脚本执行时按此名称选租户。" required /></template>
            <el-input v-model="form.authName" placeholder="例如：hive-prod" />
          </el-form-item>
          <el-form-item required>
            <template #label><SBLabel text="Principal（Kerberos 主体）" tip="格式 user/instance@REALM。修改后会清除认证测试状态。" required /></template>
            <el-input v-model="form.principal" placeholder="hive@EXAMPLE.COM" @change="onAuthChanged" />
          </el-form-item>
          <el-form-item>
            <template #label><SBLabel text="默认数据库" tip="脚本执行时使用的默认数据库。" /></template>
            <el-input v-model="form.defaultDatabase" placeholder="default" />
          </el-form-item>
          <el-form-item>
            <template #label><SBLabel text="描述" tip="对租户的简短说明。" /></template>
            <el-input v-model="form.description" type="textarea" :rows="2" placeholder="例如：开发环境 Hive 租户" />
          </el-form-item>
          <el-form-item>
            <template #label><SBLabel text="启用" tip="关闭后此租户不会出现在执行页面的下拉列表中。" /></template>
            <el-switch v-model="form.enabled" />
          </el-form-item>
        </el-form>
      </section>

      <!-- Zone 2: Keytab -->
      <section class="sb-zone">
        <h4 class="sb-zone-title">
          Keytab 文件
          <span v-if="form.keytabPath" class="sb-zone-meta mono">已配置</span>
        </h4>
        <p class="sb-zone-help">
          上传与 Principal 匹配的 Keytab 文件（小于 1 MB）。修改后会清除认证测试状态。
        </p>
        <el-upload
          ref="keytabUploadRef"
          :auto-upload="false"
          :limit="1"
          :on-change="onKeytabFile"
          :on-remove="() => { keytabFile = null }"
          accept=".keytab"
          drag
        >
          <div class="el-upload__text">拖拽 .keytab 到此处或<em>点击选择</em></div>
        </el-upload>
      </section>

      <!-- Zone 3: 测试结果 -->
      <section class="sb-zone">
        <h4 class="sb-zone-title">
          认证测试
          <span v-if="form.lastTestAt" class="sb-zone-meta">
            {{ form.lastTestOk ? '通过' : '失败' }} · {{ formatDate(form.lastTestAt) }}
          </span>
        </h4>
        <div v-if="testing" v-loading="testing" class="sb-test-loading" />
        <template v-else-if="testResult">
          <div class="sb-test-card" :class="{ ok: testResult.ok, fail: !testResult.ok }">
            <el-icon :size="22">
              <component :is="testResult.ok ? CircleCheck : CircleClose" />
            </el-icon>
            <span>{{ testResult.ok ? '通过' : '失败' }}</span>
            <span v-if="testResult.mode" class="muted">· 环境 {{ testResult.mode }}</span>
            <span v-if="testResult.kinitExit != null" class="muted">· kinit exit={{ testResult.kinitExit }}</span>
          </div>
          <pre v-if="testResult.stdout" class="sb-log">{{ testResult.stdout }}</pre>
        </template>
        <p v-else-if="form.id && !form.lastTestAt" class="muted sb-zone-help">
          尚未测试，或上次测试后修改了 Principal / Auth 名称 / Keytab。
        </p>
        <p v-else-if="!form.id" class="muted sb-zone-help">
          请先保存基本信息后再测试认证。
        </p>
      </section>

      <template #footer>
        <div style="display: flex; gap: 8px; justify-content: flex-end">
          <el-button @click="drawerOpen = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="saveOnly">保存</el-button>
          <el-button
            type="success"
            :loading="saving || testing"
            :disabled="!form.id"
            @click="saveAndTest"
          >
            保存并测试认证
          </el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, CircleCheck, CircleClose, Close } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listTenants, createTenant, updateTenant, deleteTenant,
  setTenantEnabled, uploadKeytab, testTenant,
  tenantRelatedCounts, markTenantStale
} from '../api/tenants'
import SBLabel from '../components/SBLabel.vue'

const rows = ref([])
const loading = ref(false)

const drawerOpen = ref(false)
const form = reactive({
  id: null, name: '', authName: '', principal: '', defaultDatabase: '',
  description: '', enabled: true,
  keytabPath: null, lastTestAt: null, lastTestOk: null
})
const saving = ref(false)
const keytabFile = ref(null)
const keytabUploadRef = ref(null)

// Snapshot of the originally-loaded principal/authName/keytabPath — used to
// decide whether to display the "待重新测试" banner after edits.
const original = ref({ principal: '', authName: '', keytabPath: null })

const testing = ref(false)
const testResult = ref(null)

const needsRetest = computed(() => {
  if (!form.id) return false
  if (!form.lastTestAt) return true
  return form.principal !== original.value.principal
      || form.authName   !== original.value.authName
      || (keytabFile.value != null)  // a new keytab is staged
})

async function refresh() {
  loading.value = true
  try { rows.value = (await listTenants()) || [] }
  finally { loading.value = false }
}

function resetForm() {
  Object.assign(form, {
    id: null, name: '', authName: '', principal: '',
    defaultDatabase: '', description: '', enabled: true,
    keytabPath: null, lastTestAt: null, lastTestOk: null
  })
  keytabFile.value = null
  keytabUploadRef.value?.clearFiles?.()
  testResult.value = null
  original.value = { principal: '', authName: '', keytabPath: null }
}

function snapshotOriginal(row) {
  original.value = {
    principal: row.principal || '',
    authName: row.authName || '',
    keytabPath: row.keytabPath || null
  }
}

function openCreate() {
  resetForm()
  drawerOpen.value = true
}

function openEdit(row) {
  resetForm()
  Object.assign(form, {
    id: row.id, name: row.name, authName: row.authName || '',
    principal: row.principal, defaultDatabase: row.defaultDatabase,
    description: row.description,
    enabled: row.enabled !== false,
    keytabPath: row.keytabPath || null,
    lastTestAt: row.lastTestAt || null,
    lastTestOk: row.lastTestOk ?? null
  })
  snapshotOriginal(row)
  drawerOpen.value = true
}

function onAuthChanged() {
  // Backend already clears lastTestAt on update — but if the user only types
  // and abandons, we still want to display the warning banner while editing.
  // (The actual DB write happens in saveOnly/saveAndTest.)
}

function formatDate(s) {
  if (!s) return ''
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
      + `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function persistMetadata() {
  if (!form.name || !form.principal) {
    ElMessage.warning('名称和 principal 必填')
    throw new Error('validation')
  }
  const payload = {
    name: form.name, authName: form.authName || null,
    principal: form.principal,
    defaultDatabase: form.defaultDatabase || null,
    description: form.description || null,
    enabled: form.enabled !== false
  }
  let saved
  if (form.id) {
    saved = await updateTenant(form.id, payload)
  } else {
    saved = await createTenant(payload)
    // V3 (PR-1): 回填 ID，第二次提交走 update 防重复创建
    form.id = saved.id
    form.keytabPath = saved.keytabPath
    form.lastTestAt = saved.lastTestAt
    form.lastTestOk = saved.lastTestOk
    snapshotOriginal(saved)
  }
  return saved
}

async function persistKeytab() {
  if (!keytabFile.value) return
  if (!keytabFile.value.name.toLowerCase().endsWith('.keytab')) {
    ElMessage.warning('只接受 .keytab 文件')
    throw new Error('validation')
  }
  if (keytabFile.value.size > 1024 * 1024) {
    ElMessage.warning(`keytab 文件过大（${(keytabFile.value.size / 1024).toFixed(1)} KB > 1 MB）`)
    throw new Error('validation')
  }
  await uploadKeytab(form.id, keytabFile.value)
  // Mark stale on the server side too so task-center / ⌘K see consistent state.
  try { await markTenantStale(form.id) } catch (_) { /* tolerate */ }
  // Clear the staged file so subsequent saves don't re-upload.
  keytabFile.value = null
  keytabUploadRef.value?.clearFiles?.()
}

async function saveOnly() {
  saving.value = true
  try {
    await persistMetadata()
    await persistKeytab()
    ElMessage.success('已保存')
    drawerOpen.value = false
    await refresh()
  } catch (e) {
    if (e?.message !== 'validation') console.error(e)
  } finally { saving.value = false }
}

async function saveAndTest() {
  saving.value = true
  try {
    await persistMetadata()
    await persistKeytab()
    // Refresh from server to pick up the persisted keytabPath + ensure ID is set.
    await refresh()
    // Look up the now-saved row to test against.
    const row = rows.value.find((r) => r.id === form.id)
    if (!row) throw new Error('tenant not visible after save')
    await runTest(row)
    if (!testResult.value?.ok) {
      ElMessage.warning('已保存，但认证测试未通过')
    } else {
      ElMessage.success('已保存，认证通过')
      drawerOpen.value = false
    }
  } catch (e) {
    if (e?.message !== 'validation') console.error(e)
  } finally { saving.value = false }
}

async function toggleEnabled(row, val) {
  const previous = row.enabled !== false
  row.enabled = val
  try {
    await setTenantEnabled(row.id, val)
    ElMessage.success(val ? '已启用' : '已禁用')
  } catch (_) {
    row.enabled = previous
  }
}

async function confirmDelete(row) {
  let counts = { historyCount: 0 }
  try {
    counts = (await tenantRelatedCounts(row.id)) || counts
  } catch (_) { /* tolerate */ }
  const related = counts.historyCount
    ? `\n将关联影响：${counts.historyCount} 条执行历史`
    : ''
  const ok = await ElMessageBox.confirm(
    `确认删除租户「${row.name}」？${related}`,
    '确认', { type: 'warning' }).catch(() => null)
  if (!ok) return
  await deleteTenant(row.id)
  ElMessage.success('已删除')
  await refresh()
}

function onKeytabFile(file) { keytabFile.value = file.raw }

async function runTest(row) {
  testResult.value = null
  testing.value = true
  try {
    const res = await testTenant(row.id)
    testResult.value = res
    // Sync the form fields so the banner reflects reality without a refresh.
    form.lastTestAt = new Date().toISOString()
    form.lastTestOk = !!res?.ok
    // After a successful test, the "modified" state has been cleared —
    // refresh original snapshot so the banner goes away if no further edits.
    const fresh = rows.value.find((r) => r.id === row.id)
    if (fresh) snapshotOriginal(fresh)
    original.value.lastTestAt = form.lastTestAt
  } catch (e) {
    testResult.value = { ok: false, mode: '?', error: e?.message || String(e) }
  } finally { testing.value = false }
}

onMounted(refresh)
</script>

<style scoped>
.sb-name-main { font-weight: 600; }
.sb-kt-configured {
  color: var(--sb-success);
  font-weight: 500;
}
.sb-zone {
  border: 1px solid var(--sb-border);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 14px;
  background: #fafbfc;
}
.sb-zone-title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.sb-zone-meta {
  font-size: 12px;
  font-weight: 500;
  color: var(--sb-text-2);
}
.sb-zone-help {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--sb-text-2);
}
.sb-test-loading { height: 80px; }

.sb-test-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-radius: 6px;
  font-weight: 600;
  border: 1px solid var(--sb-border);
  background: #f8fafc;
}
.sb-test-card.ok   { background: #f0fdf4; border-color: #bbf7d0; color: var(--sb-success); }
.sb-test-card.fail { background: #fef2f2; border-color: #fecaca; color: var(--sb-danger); }
.sb-log {
  background: #0b1220;
  color: #d6e2ff;
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 12px;
  max-height: 200px;
  overflow: auto;
  margin-top: 10px;
}
</style>