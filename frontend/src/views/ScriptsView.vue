<!--
  ScriptsView — table of all scripts.
  Columns: 名称 / 分类 / 描述 / 参数 / 超时 / 状态 / 最后修改 / 操作
  Row actions: 执行 / 编辑 / 更多(复制 / 启用-禁用 / 删除).
  Delete is moved into the "更多" menu to avoid accidental clicks.
-->
<template>
  <div>
    <div class="sb-header">
      <div>
        <h2 class="sb-page-title">脚本管理</h2>
        <p class="sb-page-sub">所有已注册的脚本。常用脚本可以点击星标加入收藏。</p>
      </div>
      <div>
        <el-button :icon="Refresh" plain @click="refresh" :loading="loading">刷新</el-button>
        <el-upload
          :show-file-list="false"
          :auto-upload="false"
          :on-change="onImportFile"
          accept=".zip"
        >
          <el-button plain :icon="UploadFilled">导入 .zip</el-button>
        </el-upload>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增脚本</el-button>
      </div>
    </div>

    <el-table :data="rows" v-loading="loading" class="sb-card" stripe>
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
      <el-table-column label="分类" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.category" size="small" disable-transitions>{{ row.category }}</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="风险等级" width="100">
        <template #default="{ row }">
          <el-tag
            size="small" disable-transitions effect="plain"
            :type="RISK_LEVEL_TAG_TYPE[normalizeRiskLevel(row.riskLevel)]"
          >{{ RISK_LEVEL_LABEL[normalizeRiskLevel(row.riskLevel)] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="参数" width="80" align="center">
        <template #default="{ row }">
          <span class="mono">{{ paramCounts[row.id] ?? '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="超时" width="100" align="right">
        <template #default="{ row }">{{ row.timeoutSeconds || 600 }} 秒</template>
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
      <el-table-column label="最后修改" width="160">
        <template #default="{ row }">
          <span class="muted">{{ formatDateTime(row.updateTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" :icon="VideoPlay" @click="goExecute(row)">执行</el-button>
          <el-button size="small" @click="goEdit(row)">编辑</el-button>
          <el-dropdown trigger="click" @command="(c) => onMore(c, row)">
            <el-button size="small" :icon="MoreFilled" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="copy">{{ row.favorite ? '复制' : '复制' }}</el-dropdown-item>
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

    <!-- Create Drawer -->
    <el-drawer
      v-model="createOpen"
      title="新增脚本"
      direction="rtl"
      size="480px"
    >
      <el-form :model="createForm" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" placeholder="小写字母+数字+下划线" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="createForm.displayName" placeholder="留空则使用名称" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="createForm.category" placeholder="如 Mock / Hudi / Flink" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="超时(秒)">
          <el-input-number
            v-model="createForm.timeoutSeconds"
            :min="1" :max="86400"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="风险等级">
          <el-select v-model="createForm.riskLevel" style="width: 100%">
            <el-option
              v-for="o in RISK_LEVEL_OPTIONS"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="允许并发执行">
          <el-switch v-model="createForm.allowConcurrent" />
          <span class="muted" style="margin-left: 8px">开启后多个调用可同时运行（默认同一脚本同一租户互斥）。</span>
        </el-form-item>
        <el-form-item label="上传 .sh 文件" required>
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :on-exceed="() => ElMessage.warning('只允许一个文件')"
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
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Plus, Star, StarFilled, Refresh, VideoPlay, MoreFilled, UploadFilled
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listScripts, createScript, deleteScript,
  setScriptEnabled, setScriptFavorite,
  copyScript, listParams
} from '../api/scripts'
import { exportScript, importScript } from '../api/extras'
import { formatDateTime } from '../utils/format'
import { RISK_LEVEL_OPTIONS, RISK_LEVEL_LABEL, RISK_LEVEL_TAG_TYPE, normalizeRiskLevel } from '../utils/labels'

const router = useRouter()
const rows = ref([])
const paramCounts = reactive({})
const loading = ref(false)

const createOpen = ref(false)
const createForm = reactive({
  name: '', displayName: '', category: '', description: '',
  timeoutSeconds: 600,
  riskLevel: 'READ_ONLY',
  allowConcurrent: false
})
const scriptFile = ref(null)
const uploadRef = ref(null)
const creating = ref(false)

async function refresh() {
  loading.value = true
  try {
    rows.value = (await listScripts()) || []
    // Resolve parameter counts in the background (a small N+1, fine for personal use)
    for (const k of Object.keys(paramCounts)) delete paramCounts[k]
    await Promise.all(rows.value.map(async (s) => {
      try {
        const p = await listParams(s.id)
        paramCounts[s.id] = (p || []).length
      } catch { paramCounts[s.id] = 0 }
    }))
  } finally { loading.value = false }
}

function openCreate() {
  createForm.name = ''
  createForm.displayName = ''
  createForm.category = ''
  createForm.description = ''
  createForm.timeoutSeconds = 600
  createForm.riskLevel = 'READ_ONLY'
  createForm.allowConcurrent = false
  scriptFile.value = null
  uploadRef.value?.clearFiles?.()
  createOpen.value = true
}

function onFileChange(file) { scriptFile.value = file.raw }

async function submitCreate() {
  if (!createForm.name) return ElMessage.warning('名称必填')
  if (!scriptFile.value) return ElMessage.warning('请上传 .sh 文件')
  if (!scriptFile.value.name.toLowerCase().endsWith('.sh'))
    return ElMessage.warning('只接受 .sh 文件')
  creating.value = true
  try {
    const fd = new FormData()
    fd.append('name', createForm.name)
    if (createForm.displayName) fd.append('displayName', createForm.displayName)
    if (createForm.category)     fd.append('category', createForm.category)
    if (createForm.description)  fd.append('description', createForm.description)
    fd.append('timeoutSeconds', String(createForm.timeoutSeconds))
    fd.append('enabled', 'true')
    fd.append('riskLevel', createForm.riskLevel || 'READ_ONLY')
    fd.append('allowConcurrent', String(!!createForm.allowConcurrent))
    fd.append('file', scriptFile.value)
    await createScript(fd)
    ElMessage.success('已创建')
    createOpen.value = false
    await refresh()
  } finally { creating.value = false }
}

async function toggleEnabled(row) {
  const v = row.enabled === false
  await setScriptEnabled(row.id, v)
  row.enabled = v
  ElMessage.success(v ? '已启用' : '已禁用')
}

async function toggleFavorite(row) {
  const v = !row.favorite
  await setScriptFavorite(row.id, v)
  row.favorite = v
}

async function onMore(cmd, row) {
  switch (cmd) {
    case 'copy': {
      const copy = await copyScript(row.id)
      ElMessage.success(`已复制为「${copy.displayName}」（已禁用）`)
      await refresh()
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
    URL.revokeObjectURL(url)
    ElMessage.success('已导出')
  } catch (e) {
    // surfaced
  }
}

async function onImportFile(file) {
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.zip')) {
    ElMessage.error('只接受 .zip 包')
    return
  }
  try {
    const res = await importScript(file.raw || file)
    ElMessage.success(`已导入「${res.name}」`)
    await refresh()
  } catch (e) {
    // surfaced
  }
}

async function confirmDelete(row) {
  await ElMessageBox.confirm(
    `确认删除脚本「${row.displayName || row.name}」？此操作不可恢复。`,
    '确认', { type: 'warning' })
  await deleteScript(row.id)
  ElMessage.success('已删除')
  await refresh()
}

function goEdit(row) { router.push({ name: 'script-edit', query: { id: row.id } }) }
function goExecute(row) { router.push({ name: 'execute', query: { script: row.id } }) }

onMounted(refresh)
</script>

<style scoped>
.sb-name-cell {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.sb-name-main { font-weight: 600; font-size: 13.5px; }
.sb-name-sub { font-size: 11.5px; color: var(--sb-text-3); }
.sb-star {
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 2px;
  border-radius: 4px;
  color: var(--sb-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
}
.sb-star:hover { background: #f3f4f6; color: var(--sb-text-2); }
.sb-star.active { color: #f59e0b; }
</style>