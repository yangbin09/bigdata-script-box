<!--
  ScriptEditView — full editor for a single script.

  Top-level Tabs:
    基本信息 / Shell / 参数 / 执行前检查 / 参数方案 / 版本历史

  Top action: [保存] [保存并试运行] — saves 基本信息 + Shell + 参数.
-->
<template>
  <div v-loading="loading">
    <div v-if="!script" class="sb-empty">
      <el-empty description="未选择脚本" />
      <el-button @click="$router.push('/scripts')" type="primary">返回脚本列表</el-button>
    </div>

    <template v-else>
      <div class="sb-header">
        <div>
          <h2 class="sb-page-title">编辑脚本 — {{ script.displayName || script.name }}</h2>
          <p class="sb-page-sub mono">{{ script.name }} · id={{ script.id }}</p>
        </div>
        <div>
          <el-button @click="$router.push('/scripts')">返回</el-button>
          <el-button :loading="saving" @click="saveAll(false)">保存</el-button>
          <el-button type="primary" :loading="saving" :icon="VideoPlay" @click="saveAll(true)">保存并试运行</el-button>
        </div>
      </div>

      <el-tabs v-model="activeTab">
        <!-- 基本信息 -->
        <el-tab-pane label="基本信息" name="info">
          <div class="sb-card sb-edit-section">
            <el-form :model="form" label-position="top">
              <el-form-item label="显示名">
                <el-input v-model="form.displayName" placeholder="显示在执行中心的名称" />
              </el-form-item>
              <el-form-item label="技术名称 (cli key)" required>
                <el-input v-model="form.name" placeholder="小写字母+数字+下划线" />
              </el-form-item>
              <el-form-item label="分类">
                <el-input v-model="form.category" placeholder="如 Mock / Hudi / Flink" />
              </el-form-item>
              <el-form-item label="描述">
                <el-input v-model="form.description" type="textarea" :rows="2" />
              </el-form-item>
              <el-form-item label="超时(秒)">
                <el-input-number
                  v-model="form.timeoutSeconds"
                  :min="1" :max="86400"
                  controls-position="right"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="默认租户">
                <el-select v-model="form.defaultTenantId" placeholder="不指定 (使用上次)"
                  clearable style="width: 100%">
                  <el-option v-for="t in tenants" :key="t.id"
                    :label="`${t.name} (${t.principal || '-'})`" :value="t.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="收藏">
                <el-switch v-model="form.favorite" />
              </el-form-item>
              <el-form-item label="启用">
                <el-switch v-model="form.enabled" />
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>

        <!-- Shell 正文 -->
        <el-tab-pane label="Shell" name="body">
          <div class="sb-card sb-edit-section">
            <h3 class="sb-section-title">
              脚本正文 <span class="count">{{ body.length }} chars</span>
            </h3>
            <el-input
              v-model="body"
              type="textarea"
              :rows="22"
              resize="vertical"
              spellcheck="false"
              class="mono"
              placeholder="#!/usr/bin/env bash&#10;echo hello"
            />
          </div>
        </el-tab-pane>

        <!-- 参数 -->
        <el-tab-pane label="参数" name="params">
          <div class="sb-card sb-edit-section">
            <div class="sb-params-head">
              <h3 class="sb-section-title" style="margin: 0">
                动态参数 <span class="count">{{ params.length }}</span>
              </h3>
              <el-button size="small" type="primary" plain :icon="Plus" @click="addParam">新增参数</el-button>
            </div>
            <p class="sb-help">
              执行时按 <code>--name value</code> 传给脚本；type=file 表示该参数接受上传文件。
            </p>
            <el-empty v-if="!params.length" :image-size="60" description="未声明参数" />
            <div v-for="(p, idx) in params" :key="idx" class="sb-param-row">
              <div class="sb-param-row-head">
                <span class="sb-param-idx">#{{ idx + 1 }}</span>
                <div class="sb-param-actions">
                  <el-button size="small" :icon="Top" :disabled="idx === 0" @click="moveUp(idx)" />
                  <el-button size="small" :icon="Bottom" :disabled="idx === params.length - 1" @click="moveDown(idx)" />
                  <el-button size="small" type="danger" plain :icon="Delete" @click="removeParam(idx)">移除</el-button>
                </div>
              </div>
              <el-form label-position="top" :model="p" class="sb-param-form">
                <div class="sb-param-grid">
                  <el-form-item label="name (cli key)" required>
                    <el-input v-model="p.name" placeholder="database" />
                  </el-form-item>
                  <el-form-item label="label">
                    <el-input v-model="p.label" placeholder="数据库" />
                  </el-form-item>
                  <el-form-item label="type">
                    <el-select v-model="p.type" style="width: 100%">
                      <el-option v-for="t in ALLOWED_TYPES" :key="t" :label="t" :value="t" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="required">
                    <el-switch v-model="p.required" />
                  </el-form-item>
                  <el-form-item v-if="p.type === 'select'" label="options (逗号分隔)">
                    <el-input v-model="p.options" placeholder="a,b,c" />
                  </el-form-item>
                  <el-form-item label="默认值">
                    <el-input v-if="p.type === 'textarea'" v-model="p.defaultValue" type="textarea" :rows="2" />
                    <el-input v-else v-model="p.defaultValue" />
                  </el-form-item>
                  <el-form-item label="placeholder">
                    <el-input v-model="p.placeholder" placeholder="占位提示" />
                  </el-form-item>
                  <el-form-item label="helpText">
                    <el-input v-model="p.helpText" placeholder="字段下方说明" />
                  </el-form-item>
                </div>
              </el-form>
            </div>
          </div>
        </el-tab-pane>

        <!-- 执行前检查 -->
        <el-tab-pane label="执行前检查" name="precheck">
          <div class="sb-card sb-edit-section">
            <p class="sb-help">
              每次执行前按 JSON 配置依次检查环境（kerberos、PATH 命令、文件存在、可写目录）。失败则不进入实际执行，状态为 <code>PRECHECK_FAILED</code>。
            </p>
            <el-input
              v-model="precheckJson"
              type="textarea"
              :rows="14"
              class="mono"
              placeholder='{"kerberos": true, "commands": ["spark-sql"], "files": ["/opt/client/bigdata_env"], "writableDirectories": ["/tmp"]}'
            />
            <div style="margin-top: 12px;">
              <el-button type="primary" plain :icon="VideoPlay" :loading="runningPrecheck" @click="runPrecheckNow">立即检查</el-button>
              <el-button @click="savePrecheckOnly" :loading="savingPrecheck">保存</el-button>
            </div>
            <div v-if="precheckResult" class="sb-precheck-result" :class="{ ok: precheckResult.ok, fail: !precheckResult.ok, skipped: precheckResult.skipped }">
              <div class="sb-precheck-headline">
                {{ precheckResult.skipped ? '未配置检查项 (已跳过)' : (precheckResult.ok ? '通过' : '失败') }}
                <span class="muted">{{ precheckResult.message }}</span>
              </div>
              <ul v-if="precheckResult.results && precheckResult.results.length">
                <li v-for="r in precheckResult.results" :key="r.name"
                    :class="r.ok ? 'ok' : 'fail'">
                  <span class="dot">{{ r.ok ? '●' : '○' }}</span>
                  <span class="name">{{ r.name }}</span>
                  <span class="msg">{{ r.message }}</span>
                </li>
              </ul>
            </div>
          </div>
        </el-tab-pane>

        <!-- 参数方案 -->
        <el-tab-pane label="参数方案" name="presets">
          <div class="sb-card sb-edit-section">
            <div class="sb-params-head">
              <h3 class="sb-section-title" style="margin: 0">参数方案 (preset) <span class="count">{{ presets.length }}</span></h3>
              <el-button size="small" type="primary" plain :icon="Plus" @click="openPresetForm()">新增方案</el-button>
            </div>
            <p class="sb-help">
              一个参数方案是一组参数值。执行时可一键应用；填写的字段会覆盖默认参数。
            </p>
            <el-empty v-if="!presets.length" :image-size="60" description="未定义方案" />
            <div v-for="p in presets" :key="p.id" class="sb-preset-row">
              <div>
                <div class="sb-preset-name">{{ p.name }}</div>
                <div class="sb-preset-desc muted">{{ p.description || '—' }}</div>
              </div>
              <div class="sb-preset-actions">
                <el-button size="small" @click="openPresetForm(p)">编辑</el-button>
                <el-button size="small" type="danger" plain @click="removePreset(p)">删除</el-button>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <!-- 版本历史 -->
        <el-tab-pane label="版本历史" name="versions">
          <div class="sb-card sb-edit-section">
            <div class="sb-params-head">
              <h3 class="sb-section-title" style="margin: 0">版本历史 <span class="count">{{ versions.length }}</span></h3>
              <span class="muted">每次保存脚本正文会创建一个新版本；回滚会创建一个新版本而不是删除历史。</span>
            </div>
            <el-empty v-if="!versions.length" :image-size="60" description="暂无历史" />
            <div v-for="v in versions" :key="v.id" class="sb-version-row">
              <div>
                <div class="sb-version-no">v{{ v.versionNo }}</div>
                <div class="sb-version-meta muted">{{ formatDateTime(v.createdAt) }}</div>
                <div v-if="v.remark" class="sb-version-remark">{{ v.remark }}</div>
              </div>
              <div class="sb-version-actions">
                <el-button size="small" @click="viewVersion(v)">查看</el-button>
                <el-button size="small" type="primary" plain @click="rollback(v)">回滚到此版本</el-button>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>

      <!-- Preset drawer -->
      <el-drawer v-model="presetFormOpen" :title="presetForm.id ? '编辑参数方案' : '新增参数方案'" direction="rtl" size="520px">
        <el-form :model="presetForm" label-position="top">
          <el-form-item label="名称" required>
            <el-input v-model="presetForm.name" />
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="presetForm.description" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="参数 (JSON)" required>
            <el-input v-model="presetForm.paramsJson" type="textarea" :rows="14"
              class="mono" placeholder='{"database":"default","threads":"4"}' />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="presetFormOpen = false">取消</el-button>
          <el-button type="primary" :loading="savingPreset" @click="savePreset">保存</el-button>
        </template>
      </el-drawer>

      <!-- Version viewer -->
      <el-drawer v-model="versionOpen" :title="`版本 v${activeVersion?.versionNo}`" direction="rtl" size="640px">
        <pre v-if="activeVersion" class="sb-log mono">{{ activeVersion.scriptContent }}</pre>
      </el-drawer>
    </template>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Plus, Delete, Top, Bottom, VideoPlay
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getScript, updateScript, saveBody, replaceParams
} from '../api/scripts'
import { listTenants } from '../api/tenants'
import {
  listPresets, createPreset, updatePreset, deletePreset,
  listVersions, getVersion, rollbackToVersion,
  runPrecheck as runPrecheckApi, savePrecheck
} from '../api/extras'
import { formatDateTime } from '../utils/format'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const script = ref(null)
const tenants = ref([])

const activeTab = ref('info')

const form = reactive({
  name: '', displayName: '', category: '', description: '',
  timeoutSeconds: 600, defaultTenantId: null,
  favorite: false, enabled: true
})
const body = ref('')
const params = ref([])

const precheckJson = ref('')
const precheckResult = ref(null)
const runningPrecheck = ref(false)
const savingPrecheck = ref(false)

const presets = ref([])
const presetFormOpen = ref(false)
const presetForm = reactive({ id: null, name: '', description: '', paramsJson: '{}' })
const savingPreset = ref(false)

const versions = ref([])
const versionOpen = ref(false)
const activeVersion = ref(null)

const ALLOWED_TYPES = ['text', 'number', 'select', 'boolean', 'date', 'textarea', 'file']

async function load() {
  const id = Number(route.query.id)
  if (!id) return
  loading.value = true
  try {
    const [detail, ts, ps, vs] = await Promise.all([
      getScript(id),
      listTenants().catch(() => []),
      listPresets(id).catch(() => []),
      listVersions(id).catch(() => [])
    ])
    script.value = detail.script
    Object.assign(form, {
      name: detail.script.name,
      displayName: detail.script.displayName,
      category: detail.script.category,
      description: detail.script.description,
      timeoutSeconds: detail.script.timeoutSeconds || 600,
      defaultTenantId: detail.script.defaultTenantId || null,
      favorite: !!detail.script.favorite,
      enabled: detail.script.enabled !== false
    })
    body.value = detail.body || ''
    params.value = (detail.params || []).map((p) => ({
      ...p,
      required: !!p.required,
      placeholder: p.placeholder || '',
      helpText: p.helpText || ''
    }))
    precheckJson.value = detail.script.precheckConfigJson || ''
    tenants.value = ts || []
    presets.value = ps || []
    versions.value = vs || []
  } finally { loading.value = false }
}

function addParam() {
  params.value.push({
    name: '', label: '', type: 'text', defaultValue: '',
    options: '', required: false, sortOrder: params.value.length,
    placeholder: '', helpText: ''
  })
}
function removeParam(i) { params.value.splice(i, 1) }
function moveUp(i) { if (i <= 0) return; const a = params.value[i - 1]; params.value[i - 1] = params.value[i]; params.value[i] = a }
function moveDown(i) { if (i >= params.value.length - 1) return; const a = params.value[i + 1]; params.value[i + 1] = params.value[i]; params.value[i] = a }

async function saveAll(thenExecute) {
  if (!script.value) return
  saving.value = true
  try {
    const fd = new FormData()
    fd.append('name', form.name)
    fd.append('displayName', form.displayName || form.name)
    if (form.category)    fd.append('category', form.category)
    if (form.description) fd.append('description', form.description)
    fd.append('timeoutSeconds', String(form.timeoutSeconds))
    fd.append('enabled', String(form.enabled))
    fd.append('favorite', String(form.favorite))
    if (form.defaultTenantId) fd.append('defaultTenantId', String(form.defaultTenantId))
    fd.append('precheckConfigJson', precheckJson.value || '')
    await updateScript(script.value.id, fd)
    await saveBody(script.value.id, body.value)
    const cleaned = params.value
      .filter((p) => p.name && p.name.trim())
      .map((p, i) => ({ ...p, sortOrder: i, required: !!p.required }))
    await replaceParams(script.value.id, cleaned)
    ElMessage.success('已保存')
    await load()
    if (thenExecute) router.push({ name: 'execute', query: { script: script.value.id } })
  } catch { /* surfaced */ }
  finally { saving.value = false }
}

async function savePrecheckOnly() {
  savingPrecheck.value = true
  try {
    await savePrecheck(script.value.id, { precheckConfigJson: precheckJson.value })
    ElMessage.success('已保存')
    await load()
  } finally { savingPrecheck.value = false }
}

async function runPrecheckNow() {
  runningPrecheck.value = true
  try {
    precheckResult.value = await runPrecheckApi(script.value.id)
  } finally {
    runningPrecheck.value = false
  }
}

function openPresetForm(p) {
  if (p) {
    Object.assign(presetForm, {
      id: p.id, name: p.name, description: p.description || '',
      paramsJson: p.paramsJson || '{}'
    })
  } else {
    Object.assign(presetForm, { id: null, name: '', description: '', paramsJson: '{}' })
  }
  presetFormOpen.value = true
}

async function savePreset() {
  if (!presetForm.name) return ElMessage.warning('名称必填')
  try { JSON.parse(presetForm.paramsJson) }
  catch { return ElMessage.warning('参数 JSON 格式错误') }
  savingPreset.value = true
  try {
    const payload = {
      name: presetForm.name,
      description: presetForm.description,
      paramsJson: presetForm.paramsJson
    }
    if (presetForm.id) await updatePreset(script.value.id, presetForm.id, payload)
    else               await createPreset(script.value.id, payload)
    ElMessage.success('已保存')
    presetFormOpen.value = false
    presets.value = (await listPresets(script.value.id)) || []
  } finally { savingPreset.value = false }
}

async function removePreset(p) {
  await ElMessageBox.confirm(`确认删除方案「${p.name}」？`, '确认', { type: 'warning' })
  await deletePreset(script.value.id, p.id)
  presets.value = (await listPresets(script.value.id)) || []
}

async function viewVersion(v) {
  activeVersion.value = await getVersion(script.value.id, v.id)
  versionOpen.value = true
}

async function rollback(v) {
  await ElMessageBox.confirm(
    `确认回滚到 v${v.versionNo}？会创建一个新的版本指向此版本，旧版本不会被删除。`,
    '确认', { type: 'warning' })
  const res = await rollbackToVersion(script.value.id, v.id)
  ElMessage.success(`已回滚为 v${res.versionNo}`)
  await load()
}

onMounted(load)
watch(() => route.query.id, load)
</script>

<style scoped>
.sb-empty { padding-top: 60px; text-align: center; }
.sb-edit-section { padding: 16px; }
.sb-edit-section :deep(.el-form-item) { margin-bottom: 12px; }
.sb-edit-section :deep(.el-form-item__label) { font-weight: 500; padding-bottom: 4px; }

.mono :deep(.el-textarea__inner) {
  font-family: var(--sb-mono);
  font-size: 12.5px;
  line-height: 1.55;
  background: #fafafa;
}

.sb-params-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}
.sb-help {
  color: var(--sb-text-3);
  font-size: 12.5px;
  margin: 0 0 12px 0;
}
.sb-help code {
  background: #f3f4f6;
  padding: 1px 4px;
  border-radius: 3px;
  font-family: var(--sb-mono);
  font-size: 12px;
}

.sb-param-row {
  border: 1px solid var(--sb-border);
  border-radius: 4px;
  padding: 10px 12px;
  margin-bottom: 10px;
  background: #fcfcfd;
}
.sb-param-row-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.sb-param-actions { display: flex; gap: 4px; }
.sb-param-idx {
  font-family: var(--sb-mono);
  font-size: 12px;
  color: var(--sb-text-3);
}
.sb-param-form :deep(.el-form-item) { margin-bottom: 10px; }
.sb-param-form :deep(.el-form-item__label) { padding-bottom: 2px; font-size: 12px; }
.sb-param-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 12px;
}

.sb-precheck-result {
  margin-top: 14px;
  border-radius: 6px;
  padding: 12px 14px;
  border: 1px solid var(--sb-border);
  background: #f8fafc;
}
.sb-precheck-result.ok { background: #f0fdf4; border-color: #bbf7d0; color: var(--sb-success); }
.sb-precheck-result.fail { background: #fef2f2; border-color: #fecaca; color: var(--sb-danger); }
.sb-precheck-result.skipped { color: var(--sb-text-3); }
.sb-precheck-result ul { margin: 8px 0 0; padding: 0; list-style: none; }
.sb-precheck-result li { display: grid; grid-template-columns: 16px 160px 1fr; gap: 8px; padding: 4px 0; font-size: 13px; }
.sb-precheck-result li .dot { font-family: var(--sb-mono); }
.sb-precheck-result li.ok .dot { color: var(--sb-success); }
.sb-precheck-result li.fail .dot { color: var(--sb-danger); }
.sb-precheck-result .name { font-weight: 500; }
.sb-precheck-result .msg { color: var(--sb-text-2); }

.sb-preset-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border: 1px solid var(--sb-border);
  border-radius: 4px;
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #fcfcfd;
}
.sb-preset-name { font-weight: 600; font-size: 13.5px; }
.sb-preset-desc { font-size: 12px; margin-top: 2px; }

.sb-version-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border: 1px solid var(--sb-border);
  border-radius: 4px;
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #fcfcfd;
}
.sb-version-no { font-weight: 700; font-family: var(--sb-mono); font-size: 13.5px; }
.sb-version-meta { font-size: 12px; margin-top: 2px; }
.sb-version-remark { font-size: 12px; margin-top: 4px; color: var(--sb-text-2); }
</style>