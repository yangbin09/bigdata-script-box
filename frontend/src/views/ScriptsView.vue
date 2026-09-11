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
        <el-button :icon="Files" plain @click="openTemplatePicker">从模板创建</el-button>
      </div>
    </div>

    <el-table :data="rows" v-loading="loading" class="sb-card" stripe>
      <template #empty>
        <el-empty description="还没有脚本。点击右上角「新增脚本」或「从模板创建」开始。" />
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
      <el-table-column label="超时（秒）" width="110" align="right">
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

    <!-- Create Drawer -->
    <el-drawer
      v-model="createOpen"
      title="新增脚本"
      direction="rtl"
      size="480px"
    >
      <el-form :model="createForm" label-position="top">
        <el-form-item required>
          <template #label><SBLabel text="技术名称" tip="Shell 脚本接收到的命令行参数名前缀（用于路由/API 调用）。保存后修改需谨慎。" required /></template>
          <el-input v-model="createForm.name" placeholder="小写字母+数字+下划线，例如 daily_etl" />
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="显示名" tip="在执行中心和脚本列表展示给用户的友好名称。留空则回退到技术名称。" /></template>
          <el-input v-model="createForm.displayName" placeholder="留空则使用技术名称" />
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="分类" tip="用于在执行中心将脚本分组显示。常用分类：Mock、Hudi、Flink、Hive。" /></template>
          <el-input v-model="createForm.category" placeholder="例如：Mock / Hudi / Flink" />
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="描述" tip="在脚本列表和执行中心展示的简短说明，便于协作者快速理解脚本用途。" /></template>
          <el-input v-model="createForm.description" type="textarea" :rows="2" placeholder="例如：每日凌晨同步 Hive 数据到 Hudi 表" />
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="超时时间（秒）" tip="脚本允许执行的最长时间。超过该时间后系统会终止进程。建议普通测试设置 300 秒。" /></template>
          <el-input-number
            v-model="createForm.timeoutSeconds"
            :min="1" :max="86400"
            controls-position="right"
            style="width: 100%"
            placeholder="300"
          />
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="风险等级" tip="用来提示用户脚本对外部系统的影响。只读表示安全；写操作表示会修改文件系统/数据库；危险表示删除或不可恢复。" /></template>
          <el-select v-model="createForm.riskLevel" style="width: 100%">
            <el-option
              v-for="o in RISK_LEVEL_OPTIONS"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <template #label><SBLabel text="允许并发执行" tip="默认同一脚本同一租户互斥（同一时间只能跑一次）。开启后允许多次同时运行。" /></template>
          <el-switch v-model="createForm.allowConcurrent" />
        </el-form-item>
        <el-form-item required>
          <template #label><SBLabel text="上传 Shell 文件" tip="拖拽或选择一个 .sh 文件。文件内容会成为脚本正文，可在保存后通过「编辑」继续调整。" required /></template>
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

    <!-- V2: template picker drawer. Picks a built-in template, asks for a
         unique name, posts to /api/scripts/from-template, then routes the
         user to the edit page so they can tweak before first run. -->
    <el-drawer
      v-model="tplOpen"
      title="从模板创建脚本"
      direction="rtl"
      size="520px"
    >
      <el-empty v-if="!templates.length && !tplLoading" description="暂无可用模板" />
      <div v-else>
        <div class="sb-tpl-list">
          <div
            v-for="t in templates"
            :key="t.code"
            class="sb-tpl-card"
            :class="{ active: tplPicked?.code === t.code }"
            @click="tplPicked = t"
          >
            <div class="sb-tpl-card-head">
              <strong>{{ t.name }}</strong>
              <el-tag v-if="t.category" size="small" effect="plain">{{ t.category }}</el-tag>
            </div>
            <div class="sb-tpl-card-desc">{{ t.description }}</div>
          </div>
        </div>
        <el-form v-if="tplPicked" label-position="top" class="sb-tpl-form">
          <el-form-item required>
            <template #label><SBLabel text="新脚本名称" tip="将通过此名称（技术名称）创建脚本，保存后可在编辑页面继续调整。" required /></template>
            <el-input
              v-model="tplName"
              :placeholder="`${tplPicked.code}-copy`"
            />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="tplOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="tplCreating"
          :disabled="!tplPicked || !tplName.trim()"
          @click="submitFromTemplate"
        >创建并打开编辑</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Plus, Star, StarFilled, Refresh, VideoPlay, MoreFilled, UploadFilled, Files
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listScripts, createScript, deleteScript,
  setScriptEnabled, setScriptFavorite,
  copyScript, listParams,
  scriptRelatedCounts
} from '../api/scripts'
import { exportScript, importScript } from '../api/extras'
import { listTemplates, createFromTemplate } from '../api/templates'
import { formatDateTime } from '../utils/format'
import { RISK_LEVEL_OPTIONS, RISK_LEVEL_LABEL, RISK_LEVEL_TAG_TYPE, normalizeRiskLevel } from '../utils/labels'
import SBLabel from '../components/SBLabel.vue'

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

// V2: template picker state
const tplOpen = ref(false)
const tplLoading = ref(false)
const tplCreating = ref(false)
const templates = ref([])
const tplPicked = ref(null)
const tplName = ref('')

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
  let counts = { historyCount: 0, presetCount: 0, scenarioCount: 0 }
  try {
    const r = await scriptRelatedCounts(row.id)
    counts = r?.data || counts
  } catch (_) { /* tolerate — just show no counts */ }
  const parts = []
  if (counts.historyCount)   parts.push(`${counts.historyCount} 条执行历史`)
  if (counts.presetCount)    parts.push(`${counts.presetCount} 个参数方案`)
  if (counts.scenarioCount)  parts.push(`${counts.scenarioCount} 个场景步骤引用`)
  const related = parts.length ? `\n将关联影响：${parts.join('、')}（删除后这些记录的引用将悬空）` : ''
  await ElMessageBox.confirm(
    `确认删除脚本「${row.displayName || row.name}」？此操作不可恢复。${related}`,
    '确认', { type: 'warning', dangerouslyUseHTMLString: false })
  await deleteScript(row.id)
  ElMessage.success('已删除')
  await refresh()
}

function goEdit(row) { router.push({ name: 'script-edit', query: { id: row.id } }) }
function goExecute(row) { router.push({ name: 'execute', query: { script: row.id } }) }

// V2: open the template picker and lazy-load the list. We re-load every
// time so newly-seeded templates appear without a hard refresh.
async function openTemplatePicker() {
  tplOpen.value = true
  tplPicked.value = null
  tplName.value = ''
  tplLoading.value = true
  try {
    templates.value = (await listTemplates()) || []
  } catch (_) { templates.value = [] }
  finally { tplLoading.value = false }
}

async function submitFromTemplate() {
  if (!tplPicked.value || !tplName.value.trim()) return
  tplCreating.value = true
  try {
    const script = await createFromTemplate(tplPicked.value.code, tplName.value.trim())
    ElMessage.success(`已从模板 ${tplPicked.value.name} 创建脚本`)
    tplOpen.value = false
    createOpen.value = false
    await refresh()
    goEdit(script)
  } catch (_) { /* interceptor toasted */ }
  finally { tplCreating.value = false }
}

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

/* V2: template picker cards */
.sb-tpl-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sb-tpl-card {
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  padding: 10px 12px;
  cursor: pointer;
  background: var(--el-bg-color);
}
.sb-tpl-card:hover { border-color: var(--el-color-primary); }
.sb-tpl-card.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.sb-tpl-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}
.sb-tpl-card-desc {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.sb-tpl-form { margin-top: 12px; }
</style>