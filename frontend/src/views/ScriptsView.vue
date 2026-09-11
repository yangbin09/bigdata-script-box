<!--
  ScriptsView — table of all scripts.
  "新增" opens the create/upload Drawer.
  Row actions: 编辑 (params + body), 启用切换, 删除.
-->
<template>
  <div>
    <div class="sb-header">
      <div>
        <h2 class="sb-page-title">脚本管理</h2>
        <p class="sb-page-sub">所有已注册的脚本。新增脚本需要上传 .sh 文件。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增脚本</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" class="sb-card" stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="名称" width="160">
        <template #default="{ row }">
          <span class="mono">{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="displayName" label="显示名" min-width="160" />
      <el-table-column prop="category" label="分类" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.category" size="small" disable-transitions>{{ row.category }}</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
      <el-table-column prop="timeoutSeconds" label="超时(s)" width="90" align="right" />
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
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="goEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" plain @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Create / Upload Drawer -->
    <el-drawer
      v-model="createOpen"
      title="新增脚本"
      direction="rtl"
      size="480px"
    >
      <el-form :model="createForm" label-position="top">
        <el-form-item label="名称 (cli key)" required>
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
          <el-input-number v-model="createForm.timeoutSeconds" :min="1" :max="86400" controls-position="right" style="width: 100%" />
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
            <div class="el-upload__text">
              拖拽 .sh 到此处或<em>点击选择</em>
            </div>
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
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listScripts, createScript, deleteScript, setScriptEnabled
} from '../api/scripts'

const router = useRouter()
const rows = ref([])
const loading = ref(false)

const createOpen = ref(false)
const createForm = reactive({
  name: '', displayName: '', category: '', description: '',
  timeoutSeconds: 600
})
const scriptFile = ref(null)
const uploadRef = ref(null)
const creating = ref(false)

async function refresh() {
  loading.value = true
  try { rows.value = (await listScripts()) || [] }
  finally { loading.value = false }
}

function openCreate() {
  createForm.name = ''
  createForm.displayName = ''
  createForm.category = ''
  createForm.description = ''
  createForm.timeoutSeconds = 600
  scriptFile.value = null
  uploadRef.value?.clearFiles?.()
  createOpen.value = true
}

function onFileChange(file) {
  scriptFile.value = file.raw
}

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
    fd.append('file', scriptFile.value)
    await createScript(fd)
    ElMessage.success('已创建')
    createOpen.value = false
    await refresh()
  } finally { creating.value = false }
}

async function toggleEnabled(row, val) {
  await setScriptEnabled(row.id, val)
  row.enabled = val
}

async function confirmDelete(row) {
  await ElMessageBox.confirm(`确认删除脚本「${row.name}」？此操作不可恢复。`, '确认', {
    type: 'warning'
  })
  await deleteScript(row.id)
  ElMessage.success('已删除')
  await refresh()
}

function goEdit(row) {
  router.push({ name: 'script-edit', query: { id: row.id } })
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
</style>