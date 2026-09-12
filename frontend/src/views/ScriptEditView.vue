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
          <h2 class="sb-page-title">
            编辑脚本 — {{ script.displayName || script.name }}
            <el-tag
              v-if="isDirty"
              size="small"
              type="warning"
              effect="plain"
              disable-transitions
              class="sb-dirty-tag"
            >未保存</el-tag>
          </h2>
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
              <el-form-item>
                <template #label><SBLabel text="显示名" tip="在执行中心和脚本列表展示给用户的友好名称。留空则回退到技术名称。" /></template>
                <el-input v-model="form.displayName" placeholder="例如：每日 ETL" />
              </el-form-item>
              <el-form-item required>
                <template #label><SBLabel text="技术名称" tip="Shell 脚本接收到的命令行参数名前缀（用于路由/API 调用）。保存后修改需谨慎，会影响已有执行记录。" required /></template>
                <el-input v-model="form.name" placeholder="小写字母+数字+下划线，例如 daily_etl" />
              </el-form-item>
              <el-form-item>
                <template #label><SBLabel text="分类" tip="用于在执行中心将脚本分组显示。常用分类：Mock、Hudi、Flink、Hive。" /></template>
                <el-input v-model="form.category" placeholder="例如：Mock / Hudi / Flink" />
              </el-form-item>
              <el-form-item>
                <template #label><SBLabel text="描述" tip="在脚本列表和执行中心展示的简短说明，便于协作者快速理解脚本用途。" /></template>
                <el-input v-model="form.description" type="textarea" :rows="2" placeholder="例如：每日凌晨同步 Hive 数据到 Hudi 表" />
              </el-form-item>
              <el-form-item>
                <template #label><SBLabel text="超时时间（秒）" tip="脚本允许执行的最长时间。超过该时间后系统会终止进程。建议普通测试设置 300 秒，长时间批处理可调到 3600 或更高。" /></template>
                <el-input-number
                  v-model="form.timeoutSeconds"
                  :min="1" :max="86400"
                  controls-position="right"
                  style="width: 100%"
                  placeholder="300"
                />
              </el-form-item>
              <el-form-item>
                <template #label><SBLabel text="风险等级" tip="用来提示用户脚本对外部系统的影响。只读表示安全；写操作表示会修改文件系统/数据库；危险表示删除或不可恢复。" /></template>
                <el-select v-model="form.riskLevel" style="width: 100%">
                  <el-option
                    v-for="o in RISK_LEVEL_OPTIONS"
                    :key="o.value"
                    :label="o.label"
                    :value="o.value"
                  />
                </el-select>
                <div v-if="form.riskLevel === 'DANGEROUS'" class="sb-risk-warn">
                  <el-icon><WarningFilled /></el-icon>
                  <span>执行此脚本将要求操作员输入 <code>CONFIRM</code> 才会真正运行。</span>
                </div>
              </el-form-item>
              <el-form-item>
                <template #label><SBLabel text="允许并发执行" tip="默认同一脚本同一租户互斥（同一时间只能跑一次）。开启后允许多次同时运行。" /></template>
                <el-switch v-model="form.allowConcurrent" />
              </el-form-item>
              <el-form-item>
                <template #label><SBLabel text="默认租户" tip="打开执行页面时预选的租户。留空则使用上次执行的租户，或仅有 1 个租户时自动选择。" /></template>
                <el-select v-model="form.defaultTenantId" placeholder="不指定（使用上次执行的租户）"
                  clearable style="width: 100%">
                  <el-option v-for="t in tenants" :key="t.id"
                    :label="`${t.name}（${t.principal || '-'}）`" :value="t.id" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <template #label><SBLabel text="收藏" tip="开启后此脚本会在执行中心的「常用脚本」中置顶显示。" /></template>
                <el-switch v-model="form.favorite" />
              </el-form-item>
              <el-form-item>
                <template #label><SBLabel text="启用" tip="关闭后脚本不会出现在执行中心，也无法被执行。" /></template>
                <el-switch v-model="form.enabled" />
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>

        <!-- Shell 正文 -->
        <el-tab-pane label="Shell" name="body">
          <div class="sb-card sb-edit-section">
            <h3 class="sb-section-title">
              Shell 脚本正文 <span class="count">{{ body.length }} 字符</span>
              <span class="sb-syntax-indicator" :class="syntaxClass">
                <el-icon><component :is="syntaxIcon" /></el-icon>
                <span>{{ syntaxLabel }}</span>
              </span>
            </h3>
            <p class="sb-help">
              编写 Shell 脚本。可通过 <code>$1</code> / <code>$2</code> 或 <code>--参数名 "$VAR"</code> 引用「参数」页声明的动态参数。
            </p>
            <el-input
              v-model="body"
              type="textarea"
              :rows="22"
              resize="vertical"
              spellcheck="false"
              class="mono"
              placeholder="#!/usr/bin/env bash&#10;echo &quot;hello $1&quot;"
            />
            <div v-if="syntaxErrors.length" class="sb-syntax-errors">
              <div class="sb-syntax-errors-title">语法错误（bash -n）</div>
              <ul>
                <li v-for="(e, i) in syntaxErrors" :key="i">{{ e }}</li>
              </ul>
            </div>
            <div v-else-if="syntaxWarnings.length" class="sb-syntax-warnings">
              <div class="sb-syntax-warnings-title">提示（shellcheck）</div>
              <ul>
                <li v-for="(w, i) in syntaxWarnings" :key="i">{{ w }}</li>
              </ul>
            </div>
            <div class="sb-syntax-actions">
              <el-button size="small" :icon="CircleCheck" :loading="syntaxChecking" @click="runSyntaxCheck">
                检查语法
              </el-button>
              <span class="muted">保存时会自动用 bash -n 拦截语法错误。</span>
            </div>
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
              声明脚本运行时需要的输入。执行时按 <code>--参数名 值</code> 传给脚本；选择「文件上传」时会上传到受控目录并将服务端路径作为参数传给 Shell。
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
                  <el-form-item required>
                    <template #label><SBLabel text="参数名" tip="Shell 脚本接收到的命令行参数名。例如填写 action，执行时生成 --action value。不需要填写前面的 --。" required /></template>
                    <el-input v-model="p.name" placeholder="例如：database" />
                    <div class="sb-help-inline">执行时以 <code>--参数名 值</code> 的形式传递给脚本。</div>
                  </el-form-item>
                  <el-form-item>
                    <template #label><SBLabel text="显示名称" tip="执行页面展示给用户看的字段名称。例如参数名 tableType，可以显示为「表类型」。" /></template>
                    <el-input v-model="p.label" placeholder="数据库" />
                  </el-form-item>
                  <el-form-item>
                    <template #label><SBLabel text="参数类型" tip="决定该参数在执行页面以何种控件呈现。" /></template>
                    <el-select v-model="p.type" style="width: 100%">
                      <el-option
                        v-for="t in PARAM_TYPE_OPTIONS"
                        :key="t.value"
                        :label="t.label"
                        :value="t.value"
                      />
                    </el-select>
                    <div class="sb-help-inline">{{ PARAM_TYPE_HELP[p.type] }}</div>
                  </el-form-item>
                  <el-form-item>
                    <template #label><SBLabel text="是否必填" tip="开启后，执行脚本前必须填写该参数。" /></template>
                    <el-switch v-model="p.required" />
                  </el-form-item>

                  <!-- Select options editor: shows for 下拉选择 type.
                       The backend stores a comma-separated string in p.options,
                       so we keep that data shape and only change the UI to
                       a friendly table editor. -->
                  <el-form-item v-if="p.type === 'select'" class="sb-param-full">
                    <template #label><SBLabel text="选项配置" tip="定义下拉选择参数的候选项。执行页面展示「显示名称」，实际传给 Shell 脚本的是「参数值」。" /></template>
                    <div class="sb-options-table">
                      <div class="sb-options-head">
                        <span>显示名称</span>
                        <span>参数值</span>
                        <span></span>
                      </div>
                      <div v-for="(opt, oi) in (p._options || [])" :key="oi" class="sb-options-row">
                        <el-input v-model="opt.label" placeholder="例如：查看文件" size="small" />
                        <el-input v-model="opt.value" placeholder="例如：ls" size="small" class="mono" />
                        <el-button size="small" type="danger" plain :icon="Delete" @click="removeOption(p, oi)" />
                      </div>
                      <div v-if="!p._options || !p._options.length" class="sb-options-empty">
                        暂无选项，点击下方新增。
                      </div>
                      <el-button size="small" plain :icon="Plus" @click="addOption(p)" style="margin-top: 6px">
                        新增选项
                      </el-button>
                    </div>
                  </el-form-item>
                  <el-form-item v-else />

                  <el-form-item>
                    <template #label><SBLabel text="默认值" tip="打开执行页面时自动填充的初始值，用户仍然可以修改。" /></template>
                    <el-input
                      v-if="p.type === 'textarea'"
                      v-model="p.defaultValue"
                      type="textarea"
                      :rows="2"
                      :placeholder="PARAM_TYPE_DEFAULT_PLACEHOLDER[p.type] || ''"
                    />
                    <el-input
                      v-else-if="p.type === 'select'"
                      v-model="p.defaultValue"
                      :placeholder="PARAM_TYPE_DEFAULT_PLACEHOLDER[p.type] || '可选值之一'"
                    />
                    <el-input
                      v-else
                      v-model="p.defaultValue"
                      :placeholder="PARAM_TYPE_DEFAULT_PLACEHOLDER[p.type] || ''"
                    />
                  </el-form-item>
                  <el-form-item>
                    <template #label><SBLabel text="占位提示" tip="执行页面输入框为空时显示的灰色提示文字。留空将使用参数类型对应的默认提示。" /></template>
                    <el-input v-model="p.placeholder" placeholder="例如：选择需要执行的操作" />
                  </el-form-item>
                  <el-form-item class="sb-param-full">
                    <template #label><SBLabel text="帮助说明" tip="显示在执行表单参数下方，用于说明参数用途和填写方式。" /></template>
                    <el-input v-model="p.helpText" type="textarea" :rows="2" placeholder="例如：选择需要执行的操作" />
                  </el-form-item>
                  <el-form-item label="显示条件 (visibleWhenJson)" :class="{ 'sb-param-full': true }">
                    <template #label><SBLabel text="显示条件" tip="JSON 配置：引用其他参数的值满足某条件时才显示本参数。留空 = 总是显示。" /></template>
                    <el-input v-model="p.visibleWhenJson"
                      type="textarea" :rows="2" class="mono"
                      placeholder='例如：{"param":"env","operator":"equals","value":"prod"}' />
                    <div v-if="p.visibleWhenJson && visibleWhenError(p)" class="sb-visibility-warn">
                      <el-icon><WarningFilled /></el-icon>
                      <span>{{ visibleWhenError(p) }}</span>
                    </div>
                    <div v-else-if="p.visibleWhenJson" class="sb-visibility-hint muted">
                      留空 = 总是显示。引用其他参数时使用 equals / notEquals。
                    </div>
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
              每次执行前按 JSON 配置依次检查环境（Kerberos、PATH 命令、文件存在、可写目录）。失败则不进入实际执行，状态为 <code>PRECHECK_FAILED</code>。留空表示不进行任何检查。
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
                {{ precheckResult.skipped ? '未配置检查项（已跳过）' : (precheckResult.ok ? '通过' : '失败') }}
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
              <h3 class="sb-section-title" style="margin: 0">参数方案（preset） <span class="count">{{ presets.length }}</span></h3>
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
          <el-form-item required>
            <template #label><SBLabel text="名称" tip="在执行页面下拉列表中展示的名称。建议简短、语义清晰，例如「开发环境」「生产环境」。" required /></template>
            <el-input v-model="presetForm.name" placeholder="例如：每日凌晨同步" />
          </el-form-item>
          <el-form-item>
            <template #label><SBLabel text="描述" tip="对该方案的简要说明，便于协作者理解适用场景。" /></template>
            <el-input v-model="presetForm.description" type="textarea" :rows="2" placeholder="例如：用于开发环境的小批量测试" />
          </el-form-item>
          <el-form-item required>
            <template #label><SBLabel text="参数值（JSON）" tip='键为脚本「参数」页声明的参数名，值为执行时要应用的值。例如：{&quot;database&quot;:&quot;default&quot;,&quot;threads&quot;:&quot;4&quot;}' required /></template>
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
import { onBeforeUnmount, onMounted, reactive, ref, watch, computed } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import {
  Plus, Delete, Top, Bottom, VideoPlay, WarningFilled, CircleCheck, CircleClose
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getScript, updateScript, saveBody, replaceParams, syntaxCheck
} from '../api/scripts'
import { listTenants } from '../api/tenants'
import {
  listPresets, createPreset, updatePreset, deletePreset,
  listVersions, getVersion, rollbackToVersion,
  runPrecheck as runPrecheckApi, savePrecheck
} from '../api/extras'
import { formatDateTime, isValidJson } from '../utils/format'
import { parseOptions, serializeOptions, parseVisibilityRule } from '../utils/params'
import {
  RISK_LEVEL_OPTIONS, normalizeRiskLevel,
  PARAM_TYPE_OPTIONS, PARAM_TYPE_HELP, PARAM_TYPE_DEFAULT_PLACEHOLDER, PARAM_TYPE
} from '../utils/labels'
import SBLabel from '../components/SBLabel.vue'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const script = ref(null)
const tenants = ref([])

// Monotonic token for the async syntax check: a result computed for an older
// body must never overwrite a newer one.
let syntaxSeq = 0

const activeTab = ref('info')

const form = reactive({
  name: '', displayName: '', category: '', description: '',
  timeoutSeconds: 600, defaultTenantId: null,
  favorite: false, enabled: true,
  riskLevel: 'READ_ONLY', allowConcurrent: false
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

/**
 * Options round-trip helpers. The parsing/serialising itself lives in
 * utils/params.js so the editor and the runtime form agree; these thin wrappers
 * only adapt the ScriptParam row shape used here.
 */
function optionsOf(p) { return parseOptions(p?.options) }
function optionsToString(list) { return serializeOptions(list) }

function addOption(p) {
  if (!p._options) p._options = []
  p._options.push({ label: '', value: '' })
}
function removeOption(p, i) {
  p._options.splice(i, 1)
}

async function load() {
  const id = Number(route.query.id)
  if (!id) return
  loading.value = true
  // Reset per-script editor state so the previous script's syntax errors can't
  // render against the new body while the 900 ms debounce is pending.
  syntaxSeq++
  syntaxErrors.value = []
  syntaxWarnings.value = []
  syntaxState.value = 'idle'
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
      enabled: detail.script.enabled !== false,
      riskLevel: normalizeRiskLevel(detail.script.riskLevel),
      allowConcurrent: !!detail.script.allowConcurrent
    })
    body.value = detail.body || ''
    params.value = (detail.params || []).map((p) => ({
      ...p,
      required: !!p.required,
      placeholder: p.placeholder || '',
      helpText: p.helpText || '',
      visibleWhenJson: p.visibleWhenJson || '',
      // _options is a UI-only helper that materializes p.options (a comma-
      // separated string in the backend) into [{label, value}] pairs.
      _options: optionsOf(p)
    }))
    precheckJson.value = detail.script.precheckConfigJson || ''
    tenants.value = ts || []
    presets.value = ps || []
    versions.value = vs || []
    // Snapshot current state so onBeforeRouteLeave can detect unsaved edits.
    resetDirty()
  } finally { loading.value = false }
}

// Dirty tracking. A watcher-based flag instead of comparing JSON snapshots:
// the old isDirty ran serialize() — a full JSON.stringify of form + params +
// the (up to 1 MB) script body — on every reactive update, i.e. per keystroke.
const dirty = ref(false)
function markDirty() { dirty.value = true }
function resetDirty() { dirty.value = false }

// flush: 'sync' so the marks made while load() populates the editor are
// superseded by the resetDirty() call at the end of load().
watch(form, markDirty, { deep: true, flush: 'sync' })
watch(params, markDirty, { deep: true, flush: 'sync' })
watch(precheckJson, markDirty, { flush: 'sync' })
watch(body, markDirty, { flush: 'sync' })

onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm(
      '有未保存的修改。确定离开？已编辑的内容将丢失。',
      '未保存',
      { type: 'warning', confirmButtonText: '放弃修改', cancelButtonText: '留在页面' }
    )
    return true
  } catch (_) { return false }
})

// Also guard against browser tab close / reload.
function beforeUnload(e) {
  if (dirty.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

function addParam() {
  params.value.push({
    name: '', label: '', type: 'text', defaultValue: '',
    // sortOrder is assigned from the array index on save; the editor's UI order
    // is the source of truth.
    options: '', required: false,
    placeholder: '', helpText: '', visibleWhenJson: '',
    _options: []
  })
}
async function removeParam(i) {
  const p = params.value[i]
  const label = p?.label || p?.name || `参数 #${i + 1}`
  try {
    await ElMessageBox.confirm(`确认移除参数「${label}」？此操作不会持久化，需点保存才生效。`, '确认', { type: 'warning' })
    params.value.splice(i, 1)
  } catch (_) { /* cancelled */ }
}
function moveUp(i) { if (i <= 0) return; const a = params.value[i - 1]; params.value[i - 1] = params.value[i]; params.value[i] = a }
function moveDown(i) { if (i >= params.value.length - 1) return; const a = params.value[i + 1]; params.value[i + 1] = params.value[i]; params.value[i] = a }

// Declared names, memoized: this Set used to be rebuilt inside every
// visibleWhenError() call, i.e. once per param per render (O(n²) per keystroke).
const declaredParamNames = computed(() =>
  new Set(params.value.map((q) => q.name).filter(Boolean))
)

// Validation errors keyed by param name, computed once per change instead of
// twice per param per render (the template used to call visibleWhenError(p) in
// both the v-if and the interpolation).
const visibilityErrors = computed(() => {
  const m = new Map()
  for (const p of params.value) {
    const { error } = parseVisibilityRule(p.visibleWhenJson, declaredParamNames.value)
    m.set(p, error || null)
  }
  return m
})

function visibleWhenError(p) {
  return visibilityErrors.value.get(p) || null
}

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
    fd.append('riskLevel', form.riskLevel || 'READ_ONLY')
    fd.append('allowConcurrent', String(!!form.allowConcurrent))
    fd.append('precheckConfigJson', precheckJson.value || '')
    await updateScript(script.value.id, fd)
    await saveBody(script.value.id, body.value)
    const cleaned = params.value
      .filter((p) => p.name && p.name.trim())
      .map((p, i) => {
        const trimmed = (p.visibleWhenJson || '').trim()
        const out = {
          ...p,
          sortOrder: i,
          required: !!p.required,
          visibleWhenJson: trimmed === '' ? null : trimmed
        }
        // Re-serialize the UI option editor back into the backend's
        // comma-separated string. _options itself is dropped from the payload.
        if (p.type === PARAM_TYPE.SELECT) {
          out.options = optionsToString(p._options || [])
        }
        delete out._options
        return out
      })
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
  // Clear the previous result first: keeping a stale pass/fail next to a
  // spinner is misleading if the new run fails.
  precheckResult.value = null
  try {
    // The endpoint takes a @RequestBody Map — posting no body at all made
    // Spring answer 415, so this button could never succeed.
    precheckResult.value = await runPrecheckApi(script.value.id, {
      tenantId: form.defaultTenantId || null
    })
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
  if (!isValidJson(presetForm.paramsJson)) return ElMessage.warning('参数 JSON 格式错误')
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
  const ok = await ElMessageBox.confirm(`确认删除方案「${p.name}」？`, '确认', { type: 'warning' })
    .catch(() => null)
  if (!ok) return
  await deletePreset(script.value.id, p.id)
  presets.value = (await listPresets(script.value.id)) || []
}

async function viewVersion(v) {
  activeVersion.value = await getVersion(script.value.id, v.id)
  versionOpen.value = true
}

async function rollback(v) {
  const ok = await ElMessageBox.confirm(
    `确认回滚到 v${v.versionNo}？会创建一个新的版本指向此版本，旧版本不会被删除。`,
    '确认', { type: 'warning' }).catch(() => null)
  if (!ok) return
  const res = await rollbackToVersion(script.value.id, v.id)
  // The endpoint answers { newVersion: {...} }, not the version itself.
  const vno = res?.newVersion?.versionNo
  ElMessage.success(vno ? `已回滚为 v${vno}` : '已回滚')
  await load()
}

// Switching the ?id= query while staying on this route does NOT trigger
// onBeforeRouteLeave, so the unsaved-changes guard has to live here too —
// otherwise picking another script silently discarded the edits.
onMounted(load)
watch(() => route.query.id, async (id, previous) => {
  if (id === previous) return
  if (dirty.value) {
    const ok = await ElMessageBox.confirm(
      '有未保存的修改。确定切换脚本？已编辑的内容将丢失。',
      '未保存',
      { type: 'warning', confirmButtonText: '放弃修改', cancelButtonText: '留在页面' }
    ).catch(() => null)
    if (!ok) {
      // Put the query back so the editor keeps showing the edited script.
      router.replace({ name: 'script-edit', query: previous ? { id: previous } : {} })
      return
    }
  }
  load()
})

// V2: inline syntax-check state. The indicator sits next to the body
// counter; clicking "检查语法" (or auto-checking after a debounce) calls
// the preflight endpoint and updates the error list below the editor.
const syntaxChecking = ref(false)
const syntaxErrors = ref([])
const syntaxWarnings = ref([])
const syntaxState = ref('idle') // idle | ok | error | warning
async function runSyntaxCheck() {
  const seq = ++syntaxSeq
  syntaxChecking.value = true
  const checked = body.value
  try {
    const data = await syntaxCheck(checked)
    if (seq !== syntaxSeq) return
    syntaxErrors.value = Array.isArray(data?.errors) ? data.errors : []
    syntaxWarnings.value = Array.isArray(data?.warnings) ? data.warnings : []
    syntaxState.value = data?.ok ? (syntaxWarnings.value.length ? 'warning' : 'ok') : 'error'
  } catch (_) {
    if (seq === syntaxSeq) syntaxState.value = 'idle'
  } finally {
    if (seq === syntaxSeq) syntaxChecking.value = false
  }
}
const syntaxLabel = computed(() => {
  switch (syntaxState.value) {
    case 'ok': return '语法 OK'
    case 'warning': return '有提示'
    case 'error': return '语法错误'
    default: return '未检查'
  }
})

// True when the user has edited any field since the last load / save.
// Shown as a small badge in the header so the unsaved-warning prompts
// aren't surprising.
const isDirty = computed(() => dirty.value)
const syntaxClass = computed(() => syntaxState.value)
const syntaxIcon = computed(() => syntaxState.value === 'error' ? CircleClose : CircleCheck)
// Debounced auto-check after edits stop — avoid hammering the server while
// the user types.
let syntaxTimer = null
watch(body, () => {
  if (syntaxTimer) clearTimeout(syntaxTimer)
  syntaxTimer = setTimeout(() => { runSyntaxCheck().catch(() => {}) }, 900)
})
onBeforeUnmount(() => { if (syntaxTimer) clearTimeout(syntaxTimer) })
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

.sb-risk-warn {
  margin-top: 6px;
  padding: 8px 10px;
  border-radius: 4px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: var(--sb-danger);
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
}
.sb-risk-warn code {
  background: #fff;
  border: 1px solid #fca5a5;
  padding: 0 4px;
  border-radius: 3px;
  font-family: var(--sb-mono);
}

.sb-param-full { grid-column: 1 / -1; }

.sb-help-inline {
  font-size: 12px;
  color: var(--sb-text-3);
  margin-top: 4px;
  line-height: 1.5;
}
.sb-help-inline code {
  background: #f3f4f6;
  padding: 1px 4px;
  border-radius: 3px;
  font-family: var(--sb-mono);
  font-size: 11.5px;
}

.sb-options-table {
  border: 1px solid var(--sb-border);
  border-radius: 4px;
  background: #fcfcfd;
  padding: 8px;
}
.sb-options-head {
  display: grid;
  grid-template-columns: 1fr 1fr 36px;
  gap: 8px;
  font-size: 12px;
  color: var(--sb-text-3);
  padding: 2px 4px 6px;
  border-bottom: 1px dashed var(--sb-border);
  margin-bottom: 6px;
}
.sb-options-row {
  display: grid;
  grid-template-columns: 1fr 1fr 36px;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
}
.sb-options-row:last-of-type { margin-bottom: 0; }
.sb-options-empty {
  font-size: 12px;
  color: var(--sb-text-3);
  padding: 6px 4px;
  text-align: center;
}

.sb-visibility-warn {
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--sb-danger);
  font-size: 12px;
}
.sb-visibility-hint { margin-top: 4px; font-size: 12px; }

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

.sb-syntax-indicator {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: 12px;
  font-size: 12px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 999px;
}
.sb-syntax-indicator.ok { background: #f0f9eb; color: #67c23a; }
.sb-syntax-indicator.warning { background: #fdf6ec; color: #e6a23c; }
.sb-syntax-indicator.error { background: #fef0f0; color: #f56c6c; }
.sb-syntax-indicator.idle { background: #f4f4f5; color: #909399; }
.sb-syntax-errors, .sb-syntax-warnings {
  margin-top: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 12.5px;
  max-height: 160px;
  overflow: auto;
}
.sb-syntax-errors { background: #fef0f0; border: 1px solid #fbc4c4; }
.sb-syntax-errors-title { color: #f56c6c; font-weight: 600; margin-bottom: 4px; }
.sb-syntax-warnings { background: #fdf6ec; border: 1px solid #faecd8; }
.sb-syntax-warnings-title { color: #e6a23c; font-weight: 600; margin-bottom: 4px; }
.sb-syntax-errors ul, .sb-syntax-warnings ul { margin: 0; padding-left: 20px; }
.sb-syntax-actions { margin-top: 8px; display: flex; align-items: center; gap: 8px; }
.muted { color: var(--sb-text-muted); font-size: 12.5px; }
.sb-dirty-tag { margin-left: 8px; vertical-align: middle; }
</style>