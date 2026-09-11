<!--
  ScriptEditView — full editor for a single script.

  Three logical blocks (still two columns on wide screens):
    基本信息: 显示名 / 技术名(name) / 分类 / 描述 / 超时 / 默认租户 / 收藏 / 启用
    脚本正文: textarea (auto-save on demand via "保存全部")
    参数配置: page-style cards with reorder/duplicate/delete + new-param button

  Top action: [保存] [保存并试运行]
  - 保存并试运行 saves everything then navigates to ExecuteView?script=<id>
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

      <div class="sb-edit-grid">
        <!-- Left column: info + body -->
        <div class="sb-edit-col">
          <div class="sb-card sb-edit-section">
            <h3 class="sb-section-title">基本信息</h3>
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
                <el-select
                  v-model="form.defaultTenantId"
                  placeholder="不指定 (使用上次)"
                  clearable
                  style="width: 100%"
                >
                  <el-option
                    v-for="t in tenants"
                    :key="t.id"
                    :label="`${t.name} (${t.principal || '-'})`"
                    :value="t.id"
                  />
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

          <div class="sb-card sb-edit-section">
            <h3 class="sb-section-title">
              脚本正文
              <span class="count">{{ body.length }} chars</span>
            </h3>
            <el-input
              v-model="body"
              type="textarea"
              :rows="18"
              resize="vertical"
              spellcheck="false"
              class="mono"
              placeholder="#!/usr/bin/env bash&#10;echo hello"
            />
          </div>
        </div>

        <!-- Right column: dynamic params -->
        <div class="sb-edit-col">
          <div class="sb-card sb-edit-section">
            <div class="sb-params-head">
              <h3 class="sb-section-title" style="margin: 0">
                动态参数 <span class="count">{{ params.length }}</span>
              </h3>
              <el-button size="small" type="primary" plain :icon="Plus" @click="addParam">新增参数</el-button>
            </div>

            <p class="sb-help">
              执行时按 <code>--name value</code> 传给脚本；校验和类型转换在后端。
            </p>

            <div v-if="!params.length" class="sb-no-params">
              <el-empty :image-size="60" description="未声明参数" />
            </div>

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
                    <el-input
                      v-if="p.type === 'textarea'"
                      v-model="p.defaultValue"
                      type="textarea"
                      :rows="2"
                    />
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
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Plus, Delete, Top, Bottom, VideoPlay
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  getScript, updateScript, saveBody, replaceParams
} from '../api/scripts'
import { listTenants } from '../api/tenants'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const script = ref(null)
const tenants = ref([])

const form = reactive({
  name: '', displayName: '', category: '', description: '',
  timeoutSeconds: 600, defaultTenantId: null,
  favorite: false, enabled: true
})
const body = ref('')
const params = ref([])

const ALLOWED_TYPES = ['text', 'number', 'select', 'boolean', 'date', 'textarea']

async function load() {
  const id = Number(route.query.id)
  if (!id) return
  loading.value = true
  try {
    const [detail, ts] = await Promise.all([getScript(id), listTenants().catch(() => [])])
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
    tenants.value = ts || []
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
    await updateScript(script.value.id, fd)
    await saveBody(script.value.id, body.value)
    const cleaned = params.value
      .filter((p) => p.name && p.name.trim())
      .map((p, i) => ({
        ...p,
        sortOrder: i,
        required: !!p.required
      }))
    await replaceParams(script.value.id, cleaned)
    ElMessage.success('已保存')
    await load()
    if (thenExecute) {
      router.push({ name: 'execute', query: { script: script.value.id } })
    }
  } catch {
    // interceptor surfaces the error
  } finally { saving.value = false }
}

onMounted(load)
watch(() => route.query.id, load)
</script>

<style scoped>
.sb-empty { padding-top: 60px; text-align: center; }

.sb-edit-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.sb-edit-col { display: flex; flex-direction: column; gap: 16px; }
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

.sb-no-params { padding: 12px 0; }

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
</style>