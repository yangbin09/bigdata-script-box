<!--
  ExecuteView — the front page. Reorganized for fast, high-frequency use.

  Top: search box + refresh.
  Section: 最近使用 (only if there is history; shows up to 6 distinct scripts).
  Section: 常用脚本 (favorite=true; only if any).
  Section: one block per category. Disabled scripts are hidden entirely.

  Drawer: opens directly into the execution flow for a script.
    - Header: script display name + (category · timeout N 秒).
    - Sub-row: tenant select (pre-populated from defaultTenantId > lastTenant > single-tenant-auto).
    - ParamForm: seeded with last-used params for this script (falls back to defaults).
    - Footer: [恢复默认参数] [取消] [执行脚本].
    - On run: button becomes "执行中 … N 秒", live elapsed-time ticker.
    - On finish: drawer stays open; form is replaced by ExecutionResultPanel with
      再次执行 / 修改参数 / 查看历史 / 关闭 buttons.
-->
<template>
  <div>
    <div class="sb-header">
      <div>
        <h2 class="sb-page-title">执行中心</h2>
        <p class="sb-page-sub">选择脚本，填写必要参数后执行。最近用过的脚本会出现在最顶部。</p>
      </div>
      <el-button :icon="Refresh" plain @click="refreshAll" :loading="loading">刷新</el-button>
    </div>

    <div class="sb-toolbar">
      <el-input
        v-model="search"
        placeholder="搜索脚本名称 / 描述 / 分类"
        clearable
        :prefix-icon="Search"
        class="sb-search"
      />
    </div>

    <!-- V3 (PR-5): 快捷操作 -->
    <section v-if="quickActions.length" class="sb-block">
      <div class="sb-section-head">
        <h3 class="sb-section-title">快捷操作</h3>
        <el-button text size="small" @click="showAddQuickDialog = true">
          <el-icon><Plus /></el-icon> 新建
        </el-button>
      </div>
      <div class="sb-qa-grid">
        <QuickActionCard
          v-for="qa in quickActions"
          :key="qa.id"
          :qa="qa"
          :script="scriptById(qa.scriptId)"
          :tenant="tenantById(qa.tenantId)"
          @open="openFromQuickAction"
          @edit="onEditQuickAction"
          @delete="onDeleteQuickAction"
          @run="onRunQuickAction"
        />
      </div>
    </section>

    <el-empty
      v-if="!loading && !filteredScripts.length && !search.trim()"
      description="暂无可执行脚本。先去「脚本管理」创建或调整脚本的启用状态。"
    />
    <el-empty
      v-else-if="!loading && !filteredScripts.length"
      :description="`没有匹配「${search}」的脚本。试试别的关键词，或清空搜索框查看全部。`"
    />

    <!-- 最近使用 -->
    <section v-if="recentItems.length" class="sb-block">
      <h3 class="sb-section-title">最近使用</h3>
      <div class="sb-card-grid">
        <div
          v-for="r in recentItems"
          :key="r.id"
          class="sb-script-card sb-script-card-recent"
          @click="openDrawerById(r.id)"
        >
          <div class="sb-recent-head">
            <span class="sb-recent-name">{{ recentDisplayName(r) }}</span>
            <el-tag
              v-if="r.lastStatus"
              size="small"
              :type="tagTypeOf(r.lastStatus)"
              disable-transitions
              effect="plain"
            >{{ labelOf(r.lastStatus) }}</el-tag>
            <el-tag
              v-else-if="r.lastSuccess === true"
              size="small"
              type="success"
              disable-transitions
              effect="plain"
            >成功</el-tag>
            <el-tag
              v-else-if="r.lastSuccess === false"
              size="small"
              type="danger"
              disable-transitions
              effect="plain"
            >失败</el-tag>
          </div>
          <div class="sb-recent-meta">
            <span>{{ r.lastTenantName || '—' }}</span>
            <span class="dot">·</span>
            <span>{{ formatDateTime(r.lastStartTime) }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 常用脚本 (收藏) -->
    <section v-if="favoriteScripts.length" class="sb-block">
      <h3 class="sb-section-title">
        常用脚本
        <span class="count">{{ favoriteScripts.length }}</span>
      </h3>
      <div class="sb-card-grid">
        <ScriptCard
          v-for="s in favoriteScripts"
          :key="`fav-${s.id}`"
          :script="s"
          @click="openDrawer(s)"
          @toggle-favorite="(v) => toggleFavorite(s, v)"
        />
      </div>
    </section>

    <!-- 按分类 -->
    <section
      v-for="group in groupedVisible"
      :key="group.category"
      class="sb-block"
    >
      <h3
        class="sb-section-title sb-group-title"
        @click="collapsed[group.category] = !collapsed[group.category]"
      >
        <el-icon :size="14">
          <component :is="collapsed[group.category] ? ArrowRight : ArrowDown" />
        </el-icon>
        {{ group.category }}
        <span class="count">{{ group.scripts.length }}</span>
      </h3>
      <div v-show="!collapsed[group.category]" class="sb-card-grid">
        <ScriptCard
          v-for="s in group.scripts"
          :key="s.id"
          :script="s"
          @click="openDrawer(s)"
          @toggle-favorite="(v) => toggleFavorite(s, v)"
        />
      </div>
    </section>

    <!-- 执行 Drawer -->
    <el-drawer
      v-model="drawerOpen"
      :direction="'rtl'"
      :size="drawerSize"
      :destroy-on-close="false"
      :show-close="false"
      :wrapper-closable="false"
      class="sb-exec-drawer"
    >
      <template #header>
        <div class="sb-drawer-header">
          <div>
            <div class="sb-drawer-title">
              {{ activeScript?.displayName || activeScript?.name }}
              <el-tag
                v-if="activeScript"
                size="small" disable-transitions effect="plain"
                :type="RISK_LEVEL_TAG_TYPE[normalizeRiskLevel(activeScript.riskLevel)]"
                class="sb-title-tag"
              >{{ RISK_LEVEL_LABEL[normalizeRiskLevel(activeScript.riskLevel)] }}</el-tag>
            </div>
            <div v-if="activeScript" class="sb-exec-sub">
              <span>{{ activeScript.category || '默认' }}</span>
              <span class="dot">·</span>
              <span>超时 {{ activeScript.timeoutSeconds || 600 }} 秒</span>
              <span class="dot">·</span>
              <router-link
                :to="{ name: 'script-edit', query: { id: activeScript.id } }"
                class="sb-exec-edit-link"
                target="_blank"
              >查看脚本定义 →</router-link>
            </div>
          </div>
          <el-button text :icon="Close" @click="closeDrawer" />
        </div>
      </template>

      <template v-if="activeScript">
        <!-- V3 (PR-4): 抽屉内左右分栏（宽屏）。左：表单（运行中 disabled）；右：实时日志 + 结果。 -->
        <div class="sb-exec-split" :class="{ 'sb-exec-narrow': narrowScreen }">
          <div class="sb-exec-left">
            <div class="sb-form-section">
              <div class="sb-form-section-label">租户</div>
              <el-select
                v-model="tenantId"
                :disabled="running"
                style="width: 100%"
              >
                <el-option
                  v-for="t in enabledTenants"
                  :key="t.id"
                  :label="`${t.name}（${t.principal || '-'}）`"
                  :value="t.id"
                />
              </el-select>
            </div>

            <div class="sb-form-section">
              <div class="sb-form-section-label">
                参数方案 <span class="muted">(可选，覆盖默认参数)</span>
              </div>
              <el-select
                v-model="presetId"
                :disabled="running"
                clearable
                placeholder="不指定 (使用默认参数)"
                style="width: 100%"
                @change="applyPresetToForm"
              >
                <el-option
                  v-for="p in presets"
                  :key="p.id"
                  :label="p.name"
                  :value="p.id"
                />
              </el-select>
            </div>

            <!-- V3 (PR-3): 4 来源徽章（默认 / 上次 / 方案 / 草稿）+ 撤销 -->
            <div class="sb-form-section sb-draft-badges">
              <div class="sb-draft-row">
                <el-tag
                  v-for="s in sourceBadges"
                  :key="s.id"
                  :type="draft.currentSource === s.id ? 'primary' : 'info'"
                  :effect="draft.currentSource === s.id ? 'dark' : 'plain'"
                  :disabled="!s.available || running"
                  class="sb-draft-badge"
                  @click="onPickSource(s.id)"
                >
                  {{ s.label }}
                  <span v-if="s.hint" class="sb-draft-hint">{{ s.hint }}</span>
                </el-tag>
                <el-button
                  v-if="canUndo"
                  size="small"
                  text
                  :disabled="running"
                  @click="onUndo"
                >撤销 ({{ undoLeft }}s)</el-button>
                <el-button
                  v-if="draft.hasDraft() && draft.currentSource !== 'draft'"
                  size="small"
                  text
                  type="warning"
                  @click="onClearDraft"
                >清除草稿</el-button>
              </div>
              <div v-if="draft.currentSource === 'draft'" class="sb-draft-banner">
                <el-icon><Document /></el-icon>
                <span>当前显示来自未提交的本地草稿（按来源徽章切换会覆盖）</span>
              </div>
            </div>

            <div class="sb-form-section">
              <ParamForm
                ref="formRef"
                :params="activeParams"
                :initial-values="lastParams"
                v-model="formValues"
              />
            </div>

            <!-- 文件参数 -->
            <div
              v-if="fileParamNames.length"
              class="sb-form-section"
            >
              <div class="sb-form-section-label">文件参数</div>
              <div v-for="p in fileParamNames" :key="p" class="sb-file-row">
                <div class="sb-file-row-label">{{ p }}</div>
                <el-upload
                  :auto-upload="true"
                  :show-file-list="false"
                  :http-request="(opts) => uploadFileFor(opts.file, p)"
                  :before-upload="(f) => beforeUploadFile(f, p)"
                  accept="*"
                >
                  <el-button size="small" :icon="UploadFilled">选择文件</el-button>
                </el-upload>
                <span v-if="fileInputs[p]" class="sb-file-name">
                  {{ fileInputs[p].originalName }} ({{ formatBytes(fileInputs[p].bytes) }})
                </span>
                <el-button v-else size="small" link type="info" disabled>未上传</el-button>
              </div>
            </div>

            <!-- 批量执行 -->
            <div class="sb-form-section">
              <div class="sb-form-section-label">
                <el-checkbox v-model="batchMode" :disabled="running">批量执行</el-checkbox>
                <span class="muted" style="margin-left: 8px">从 Excel 直接粘贴列；每行对应一次执行</span>
              </div>
              <BatchTableEditor
                v-if="batchMode"
                ref="batchEditorRef"
                v-model="batchRows"
                :params="activeScript?.params || []"
                :disabled="running"
                @update:invalid-count="(n) => (batchInvalidCount = n)"
              />
            </div>
          </div>

          <!-- V3 (PR-4): 右侧实时日志 + 结果面板。运行中拉 /log-tail，结束后替换为结果面板 -->
          <div class="sb-exec-right">
            <div class="sb-exec-right-header">
              <span v-if="runningExecutionId">
                执行中 · {{ elapsed }}s · #{{ runningExecutionId }}
              </span>
              <span v-else-if="resultHistory">
                结果 · #{{ resultHistory.id }} · {{ formatDateTime(resultHistory.startTime) }}
              </span>
              <span v-else class="muted">日志 / 结果</span>
            </div>
            <div v-if="!resultHistory && running" class="sb-live-log">
              <pre class="sb-log-stdout">{{ liveLog }}</pre>
              <div v-if="liveLogError" class="sb-log-error">
                <el-alert type="error" :closable="false" show-icon>
                  读取日志失败 <el-button size="small" text @click="refreshLiveLog">重试</el-button>
                </el-alert>
              </div>
            </div>
            <ExecutionResultPanel
              v-else-if="resultHistory"
              :history="resultHistory"
              :log-stdout="resultStdout"
              :log-stderr="resultStderr"
              @rerun="rerunFromResult"
              @edit="backToForm"
              @view-history="goHistory"
            />
            <div v-else class="sb-right-empty muted">
              提交后会在这里实时显示日志，完成后展示结果摘要。
            </div>
          </div>
        </div>
      </template>

      <template #footer>
        <div v-if="activeScript" class="sb-exec-footer">
          <!-- 表单页 footer -->
          <template v-if="!resultHistory">
            <el-button :disabled="running" @click="resetDefaults">恢复默认参数</el-button>
            <div class="right">
              <el-button @click="openPreview" :disabled="running" :loading="previewing">预览</el-button>
              <el-button @click="openAddQuickFromForm" :disabled="running">保存为快捷操作</el-button>
              <el-button @click="closeDrawer" :disabled="running">取消</el-button>
              <!-- V2: cancel button appears only while a script is running.
                   Sends POST /executions/{id}/cancel for the running execution
                   that matches the active script + tenant pair. -->
              <el-button
                v-if="running"
                type="danger"
                plain
                :loading="cancelling"
                :icon="CircleClose"
                @click="cancelRunning"
              >取消执行</el-button>
              <el-button
                type="primary"
                :icon="running ? Loading : VideoPlay"
                :loading="running"
                :disabled="!tenantId"
                @click="runScript"
              >{{ running ? `执行中… ${elapsed}s` : (batchMode ? '批量执行' : '执行脚本') }}</el-button>
            </div>
          </template>
          <!-- 结果页 footer -->
          <template v-else>
            <el-button @click="closeDrawer">关闭</el-button>
            <div class="right">
              <el-button :icon="EditPen" @click="backToForm">修改参数</el-button>
              <el-button :icon="RefreshRight" type="primary" @click="rerunFromResult">再次执行</el-button>
            </div>
          </template>
        </div>
      </template>
    </el-drawer>

    <!-- 预览 Drawer -->
    <el-drawer
      v-model="previewOpen"
      title="执行预览 (Dry Run)"
      direction="rtl"
      size="560px"
      :show-close="false"
      class="sb-exec-drawer"
    >
      <template #header>
        <div class="sb-drawer-header">
          <div class="sb-drawer-title">执行预览 — {{ activeScript?.displayName || activeScript?.name }}</div>
          <el-button text :icon="Close" @click="previewOpen = false" />
        </div>
      </template>
      <template v-if="previewData">
        <h4 class="sb-test-section-title">命令</h4>
        <pre class="sb-log sb-cmd">{{ previewData.command.join(' ') }}</pre>
        <h4 class="sb-test-section-title">命令行参数（--参数名 参数值）</h4>
        <pre class="sb-log">{{ JSON.stringify(previewData.params, null, 2) }}</pre>
        <h4 class="sb-test-section-title">环境变量（敏感已脱敏）</h4>
        <pre class="sb-log">{{ JSON.stringify(previewData.globalVariables, null, 2) }}</pre>
        <div class="sb-preview-meta">
          <div><span class="muted">租户：</span>{{ previewData.tenantName }}</div>
          <div><span class="muted">principal：</span>{{ previewData.principal || '—' }}</div>
          <div><span class="muted">超时：</span>{{ previewData.timeoutSeconds }} 秒</div>
          <div><span class="muted">kinit 包装：</span>{{ previewData.kinitWrapped ? '是' : '否' }}</div>
          <div><span class="muted">keytab：</span>{{ previewData.keytabPath || '未配置' }}</div>
        </div>
      </template>
      <template v-else>
        <div v-loading="previewing" style="height: 80px" />
      </template>
    </el-drawer>

    <!-- 批量执行结果 Drawer -->
    <el-drawer
      v-model="batchResultOpen"
      :title="`批量执行结果 — ${batchResult?.batchId || ''}`"
      direction="rtl"
      size="640px"
      :show-close="false"
      class="sb-exec-drawer"
    >
      <template v-if="batchResult">
        <div class="sb-status-card ok">
          <div class="sb-status-headline">成功 {{ batchResult.succeeded }} / 失败 {{ batchResult.failed }} / 总 {{ batchResult.total }}</div>
        </div>
        <el-table :data="batchRowsForTable" class="sb-card" stripe>
          <el-table-column label="#" width="60">
            <template #default="{ $index }">#{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column label="脚本" prop="scriptName" min-width="200" />
          <el-table-column label="租户" prop="tenantName" min-width="120" />
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.success ? 'success' : 'danger'" disable-transitions effect="plain">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="100" align="right">
            <template #default="{ row }">{{ row.durationMs || 0 }} ms</template>
          </el-table-column>
          <el-table-column label="执行记录" width="110" align="right">
            <template #default="{ row }">
              <span class="mono">{{ row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="center" fixed="right">
            <template #default="{ $index }">
              <el-button
                size="small"
                link
                type="primary"
                :loading="retryingRow === $index"
                @click="retryBatchRow($index)"
              >重试此行</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <!-- V3 (PR-5): 新建 / 编辑快捷操作 对话框 -->
    <el-dialog
      v-model="showAddQuickDialog"
      :title="editingQuickAction ? '编辑快捷操作' : '新建快捷操作'"
      width="500px"
      :close-on-click-modal="false"
      @closed="resetQuickForm"
    >
      <el-form :model="quickForm" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="quickForm.name" placeholder="例如：日常巡检" />
        </el-form-item>
        <el-form-item label="图标（emoji 或留空）">
          <el-input v-model="quickForm.icon" placeholder="⚡" />
        </el-form-item>
        <el-form-item label="脚本" required>
          <el-select v-model="quickForm.scriptId" style="width:100%" filterable>
            <el-option
              v-for="s in scripts"
              :key="s.id"
              :label="`${s.displayName || s.name} (#${s.id})`"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="租户" required>
          <el-select v-model="quickForm.tenantId" style="width:100%">
            <el-option
              v-for="t in enabledTenants"
              :key="t.id"
              :label="`${t.name}（${t.principal || '-'}）`"
              :value="t.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="参数（JSON）">
          <el-input
            v-model="quickForm.paramsJson"
            type="textarea"
            :rows="5"
            placeholder='{"参数": "值"}'
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddQuickDialog = false">取消</el-button>
        <el-button type="primary" @click="submitQuickForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Refresh, Search, VideoPlay, Loading, Close, ArrowRight, ArrowDown, CircleClose,
  EditPen, RefreshRight, UploadFilled, Document, Plus
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listScripts, getScript, setScriptFavorite } from '../api/scripts'
import { listTenants } from '../api/tenants'
import { execute, submitExecution, readStdout, readStderr, cancelExecution, activeExecutions, executionState, logTail } from '../api/executions'
import { useExecutionStore } from '../stores/executionStore'
import { recentScripts } from '../api/history'
import { listPresets, dryRun, uploadFile, runBatch, getBatch, retryBatchRow as apiRetryBatchRow } from '../api/extras'
import ScriptCard from '../components/ScriptCard.vue'
import ParamForm from '../components/ParamForm.vue'
import ExecutionResultPanel from '../components/ExecutionResultPanel.vue'
import QuickActionCard from '../components/QuickActionCard.vue'
import BatchTableEditor from '../components/BatchTableEditor.vue'
import { listQuickActions, createQuickAction, updateQuickAction, deleteQuickAction } from '../api/quickActions'
import { formatDateTime, formatBytes } from '../utils/format'
import { getItem, setItem, takeSessionItem, KEYS, draftKey } from '../utils/storage'
import { useParamDraft, DRAFT_SRC } from '../composables/useParamDraft'
import {
  RISK_LEVEL, RISK_LEVEL_LABEL, RISK_LEVEL_TAG_TYPE, normalizeRiskLevel, RISK_CONFIRM_TOKEN,
  labelOf, tagTypeOf
} from '../utils/labels'

const router = useRouter()
const scripts = ref([])
const tenants = ref([])
const recentList = ref([])
const loading = ref(false)

// V3 (PR-5): 快捷操作
const quickActions = ref([])
const showAddQuickDialog = ref(false)
const editingQuickAction = ref(null)
const quickForm = reactive({
  name: '', icon: '⚡', scriptId: null, tenantId: null,
  paramsJson: '{}', presetId: null
})

const search = ref('')
const collapsed = reactive({})

// Drawer state
const drawerOpen = ref(false)
const activeScript = ref(null)
const activeParams = ref([])
const formValues = ref({})
const tenantId = ref(null)
const formRef = ref(null)
const running = ref(false)
const elapsed = ref(0)
let elapsedTimer = null
const cancelling = ref(false)  // V2: true while a cancel request is in-flight

// V3 (PR-2): 当前正在运行的 executionId。开启异步路径后，runScript 不再
// 阻塞等待 submit 结果，而是把 executionId 塞回这里，由 executionStore
// 的全局轮询驱动 UI 状态。这样切到别的页面也不会丢进度，回来能直接看到。
const runningExecutionId = ref(null)
const exec = useExecutionStore()

// V3 (PR-3): 参数草稿 + 4 个来源徽章切换。
const draft = useParamDraft({
  getScriptId: () => activeScript.value?.id,
  getTenantId: () => tenantId.value,
  getParams: () => activeParams.value,
  getPresets: () => presets.value,
  getPresetId: () => presetId.value,
  formValues,
  applyValues: (vals) => { formValues.value = { ...vals } }
})

async function waitForTerminal(executionId) {
  // 轮询单条直到终态。executionStore.bootstrap 已经启动了全局 tick，
  // 这里只需要在它即将结束前拉到最新一次 stdout/stderr。
  const script = activeScript.value
  const timeoutMs = ((Number(script?.timeoutSeconds || 600) + 60) * 1000) + 60000
  const deadline = Date.now() + timeoutMs
  return new Promise((resolve, reject) => {
    const stop = exec.$subscribe(() => {
      const v = exec.byId.get(Number(executionId))
      if (!v) return
      if (exec.isTerminal(v.status)) {
        stop()
        resolve(v)
      }
    })
    setTimeout(() => { stop(); reject(new Error('wait timeout')) }, Math.max(1000, deadline - Date.now()))
  })
}

// V1.5: preset, file inputs, dry-run preview, batch
const presets = ref([])
const presetId = ref(null)
const fileInputs = reactive({})          // paramName -> {serverPath, originalName, bytes}
const previewOpen = ref(false)
const previewData = ref(null)
const previewing = ref(false)
const batchMode = ref(false)
const batchRows = ref([])
const batchEditorRef = ref(null)
const batchInvalidCount = ref(0)
const batchResult = ref(null)
const batchResultOpen = ref(false)
const batchRowsForTable = ref([])
// V3 (PR-8): tracks which row's retry is currently in flight so the
// spinner can target a single row instead of disabling the whole table.
const retryingRow = ref(-1)

// Result state
const resultHistory = ref(null)
const resultStdout = ref('')
const resultStderr = ref('')

// V3 (PR-3): 来源徽章渲染数据 + 撤销倒计时。
let nowTimer = null

// V3 (PR-4): 实时日志（运行中）/ 窄屏判断。
const liveLog = ref('')
const liveLogError = ref(false)
let liveLogTimer = null
const narrowScreen = ref(false)
function checkNarrow() {
  narrowScreen.value = typeof window !== 'undefined' && window.innerWidth < 1024
}
checkNarrow()
const sourceBadges = computed(() => [
  { id: DRAFT_SRC.DEFAULT, label: '默认参数', available: true,
    hint: (activeParams.value || []).some((p) => p.defaultValue != null) ? '· 有默认值' : '' },
  { id: DRAFT_SRC.LAST, label: '上次执行', available: draft.hasLast(),
    hint: draft.hasLast() ? '' : '· 无历史' },
  { id: DRAFT_SRC.PRESET, label: '指定方案', available: !!presetId.value,
    hint: presetId.value ? '' : '· 未选' },
  { id: DRAFT_SRC.DRAFT, label: '未提交草稿', available: draft.hasDraft(),
    hint: draft.hasDraft() ? '' : '· 无草稿' }
])
const canUndo = ref(false)
const undoLeft = ref(0)
function onPickSource(id) {
  if (running.value) return
  const next = sourceBadges.value.find((s) => s.id === id)
  if (!next || !next.available) {
    ElMessage.warning(`来源「${next?.label || id}」当前不可用`)
    return
  }
  draft.applySource(id)
  canUndo.value = true
  undoLeft.value = 5
  if (nowTimer) clearInterval(nowTimer)
  nowTimer = setInterval(() => {
    undoLeft.value = Math.max(0, undoLeft.value - 1)
    if (undoLeft.value === 0) {
      canUndo.value = false
      clearInterval(nowTimer)
      nowTimer = null
    }
  }, 1000)
}
function onUndo() {
  if (draft.undo()) {
    ElMessage.success('已撤销')
    canUndo.value = false
    if (nowTimer) { clearInterval(nowTimer); nowTimer = null }
  } else {
    ElMessage.info('已超过撤销窗口')
  }
}
function onClearDraft() {
  draft.clearDraft()
  ElMessage.success('草稿已清除')
  // 草稿清除后若当前显示是草稿，回退到默认
  if (draft.currentSource.value === 'draft') {
    draft.applySource(DRAFT_SRC.DEFAULT)
  }
}

// V3 (PR-4): 实时日志拉取（增量）
async function refreshLiveLog() {
  if (!runningExecutionId.value) return
  try {
    const text = await logTail(runningExecutionId.value, 'stdout', 65536)
    liveLogError.value = false
    // 服务端返回完整文件末尾；前端只在新内容时更新（O(1) 字符串比较）
    if (typeof text === 'string' && text !== liveLog.value) {
      liveLog.value = text
      await nextTick()
      const el = document.querySelector('.sb-log-stdout')
      if (el) el.scrollTop = el.scrollHeight
    }
  } catch (e) {
    liveLogError.value = true
  }
}

function startLiveLog() {
  liveLog.value = ''
  liveLogError.value = false
  refreshLiveLog()
  if (liveLogTimer) clearInterval(liveLogTimer)
  liveLogTimer = setInterval(refreshLiveLog, 2000)
}
function stopLiveLog() {
  if (liveLogTimer) { clearInterval(liveLogTimer); liveLogTimer = null }
}

// V3 (PR-5): 快捷操作 helpers
function scriptById(id) { return (scripts.value || []).find((s) => s.id === id) || null }
function tenantById(id) { return (tenants.value || []).find((t) => t.id === id) || null }

async function openFromQuickAction(qa) {
  const s = scriptById(qa.scriptId)
  if (!s) {
    ElMessage.warning('关联脚本已不存在')
    return
  }
  if (s.enabled === false) {
    ElMessage.warning('脚本已禁用')
    return
  }
  await openDrawer(s)
  // Apply preset/params
  if (qa.tenantId) tenantId.value = qa.tenantId
  if (qa.presetId) {
    presetId.value = qa.presetId
    await applyPresetToForm(qa.presetId)
  }
  if (qa.paramsJson) {
    try {
      const vals = JSON.parse(qa.paramsJson)
      const next = { ...formValues.value }
      for (const [k, v] of Object.entries(vals)) next[k] = v == null ? '' : String(v)
      formValues.value = next
    } catch (_) { /* tolerate */ }
  }
}

function onEditQuickAction(qa) {
  editingQuickAction.value = qa
  Object.assign(quickForm, {
    name: qa.name, icon: qa.icon || '⚡',
    scriptId: qa.scriptId, tenantId: qa.tenantId,
    paramsJson: qa.paramsJson || '{}', presetId: qa.presetId
  })
  showAddQuickDialog.value = true
}

async function onDeleteQuickAction(qa) {
  await ElMessageBox.confirm(`确认删除快捷操作「${qa.name}」？`, '确认', { type: 'warning' })
  await deleteQuickAction(qa.id)
  ElMessage.success('已删除')
  await refreshAll()
}

async function onRunQuickAction(qa) {
  await openFromQuickAction(qa)
  // Trigger execution — same gate as DANGEROUS risk applies (handled in runScript)
  await runScript()
}

function resetQuickForm() {
  Object.assign(quickForm, {
    name: '', icon: '⚡', scriptId: null, tenantId: null,
    paramsJson: '{}', presetId: null
  })
  editingQuickAction.value = null
}

async function submitQuickForm() {
  if (!quickForm.name || !quickForm.scriptId || !quickForm.tenantId) {
    ElMessage.warning('名称、脚本、租户必填')
    return
  }
  const payload = {
    name: quickForm.name,
    icon: quickForm.icon,
    scriptId: quickForm.scriptId,
    tenantId: quickForm.tenantId,
    paramsJson: quickForm.paramsJson,
    presetId: quickForm.presetId || null
  }
  if (editingQuickAction.value) {
    await updateQuickAction(editingQuickAction.value.id, payload)
  } else {
    await createQuickAction(payload)
  }
  ElMessage.success('已保存')
  showAddQuickDialog.value = false
  resetQuickForm()
  await refreshAll()
}

function openAddQuickFromForm() {
  resetQuickForm()
  if (activeScript.value) quickForm.scriptId = activeScript.value.id
  if (tenantId.value) quickForm.tenantId = tenantId.value
  // 预填当前表单参数
  const snap = {}
  for (const [k, v] of Object.entries(formValues.value || {})) {
    if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') {
      snap[k] = v
    }
  }
  quickForm.paramsJson = JSON.stringify(snap, null, 2)
  showAddQuickDialog.value = true
}

const LAST_TENANT_KEY = KEYS.LAST_TENANT
const LAST_PARAMS_KEY = KEYS.LAST_PARAMS

const enabledTenants = computed(() =>
  (tenants.value || []).filter((t) => t.enabled !== false)
)

const filteredScripts = computed(() => {
  const term = search.value.trim().toLowerCase()
  return (scripts.value || []).filter((s) => {
    if (s.enabled === false) return false
    if (!term) return true
    return (s.displayName || s.name || '').toLowerCase().includes(term)
      || (s.description || '').toLowerCase().includes(term)
      || (s.category || '').toLowerCase().includes(term)
      || (s.name || '').toLowerCase().includes(term)
  })
})

const favoriteScripts = computed(() =>
  filteredScripts.value.filter((s) => s.favorite)
)

const groupedVisible = computed(() => {
  const favSet = new Set(favoriteScripts.value.map((s) => s.id))
  const map = new Map()
  for (const s of filteredScripts.value) {
    if (favSet.has(s.id)) continue
    const k = s.category || '默认'
    if (!map.has(k)) map.set(k, [])
    map.get(k).push(s)
  }
  // Sort: non-Mock categories first, Mock last
  const list = [...map.entries()].map(([category, list]) => ({ category, scripts: list }))
  list.sort((a, b) => {
    const am = a.category === 'Mock' ? 1 : 0
    const bm = b.category === 'Mock' ? 1 : 0
    return am - bm
  })
  return list
})

const recentItems = computed(() => {
  const byId = new Map(scripts.value.map((s) => [s.id, s]))
  return (recentList.value || []).filter((r) => byId.has(r.id))
})

function recentDisplayName(r) {
  const s = scripts.value.find((x) => x.id === r.id)
  return s ? (s.displayName || s.name) : (r.scriptName || '')
}

// V2: full status vocabulary for the recent card tag comes from utils/labels
// (this file used to keep a private copy of the same maps).

function pickInitialTenant(script) {
  // 1) explicit default on the script
  if (script?.defaultTenantId && enabledTenants.value.some((t) => t.id === script.defaultTenantId))
    return script.defaultTenantId
  // 2) last tenant from localStorage
  const last = getItem(LAST_TENANT_KEY)
  if (last && enabledTenants.value.some((t) => t.id === last)) return last
  // 3) only one tenant -> auto-pick
  if (enabledTenants.value.length === 1) return enabledTenants.value[0].id
  return null
}

function lastParamsForScript() {
  if (!activeScript.value) return {}
  const all = getItem(LAST_PARAMS_KEY, {}) || {}
  return all[activeScript.value.id] || {}
}

// Stable reference for the drawer's seed values. Passing the function itself
// (":initial-values="lastParamsForScript") built a new object on every render,
// which made ParamForm reseed — and discard in-progress edits — whenever an
// unrelated part of this view re-rendered (e.g. after uploading a file).
const lastParams = computed(() => lastParamsForScript())

async function refreshAll() {
  loading.value = true
  try {
    const [s, t, r, qa] = await Promise.all([
      listScripts(),
      listTenants(),
      recentScripts(6).catch(() => []),
      listQuickActions().catch(() => [])
    ])
    scripts.value = s || []
    tenants.value = t || []
    recentList.value = r || []
    quickActions.value = qa || []
  } finally {
    loading.value = false
  }
}

async function openDrawerById(id) {
  const s = scripts.value.find((x) => x.id === id)
  if (!s) return ElMessage.warning('脚本已被删除')
  await openDrawer(s)
}

// Honor a one-shot rerun handoff placed by HistoryView.
async function consumeRerun() {
  const payload = takeSessionItem(KEYS.RERUN)
  if (!payload?.scriptId) return
  try {
    const s = scripts.value.find((x) => x.id === payload.scriptId)
    if (!s) {
      ElMessage.warning('原脚本已不存在，无法重跑')
      return
    }
    if (s.enabled === false) {
      ElMessage.warning('脚本已禁用，无法重跑')
      return
    }
    await openDrawer(s)
    // After form is open, apply the rerun overrides
    if (payload.tenantId && enabledTenants.value.some((t) => t.id === payload.tenantId)) {
      tenantId.value = payload.tenantId
    }
    if (payload.params && typeof payload.params === 'object') {
      // Merge over formValues so unrecognized params don't show up
      const next = { ...formValues.value }
      for (const [k, v] of Object.entries(payload.params)) next[k] = v == null ? '' : String(v)
      formValues.value = next
    }
  } catch (_) { /* ignore malformed payload */ }
}

async function openDrawer(s) {
  if (s.enabled === false) {
    ElMessage.warning('脚本已禁用，无法执行')
    return
  }
  activeScript.value = s
  resultHistory.value = null
  resultStdout.value = ''
  resultStderr.value = ''
  elapsed.value = 0
  presetId.value = null
  batchMode.value = false
  batchRows.value = []
  batchInvalidCount.value = 0
  batchResult.value = null
  for (const k of Object.keys(fileInputs)) delete fileInputs[k]
  try {
    const [detail, ps] = await Promise.all([
      getScript(s.id),
      listPresets(s.id).catch(() => [])
    ])
    activeParams.value = detail?.params || []
    presets.value = ps || []
  } catch {
    activeParams.value = []
    presets.value = []
  }
  formValues.value = {}
  tenantId.value = pickInitialTenant(s)
  drawerOpen.value = true
  // V3 (PR-3): 抽屉打开后按上次的来源徽章自动恢复一次。
  // 没有草稿 / 没有 last / 没有 preset 时回退默认；不会破坏单 session 内的"先默认值"语义。
  draft.applySource(draft.currentSource.value || DRAFT_SRC.DEFAULT)
}

const fileParamNames = computed(() =>
  (activeParams.value || []).filter((p) => p.type === 'file').map((p) => p.name)
)

async function applyPresetToForm(pid) {
  if (!pid) return
  const p = presets.value.find((x) => x.id === pid)
  if (!p) return
  let parsed = {}
  try { parsed = p.paramsJson ? JSON.parse(p.paramsJson) : {} } catch {}
  const next = { ...formValues.value }
  for (const [k, v] of Object.entries(parsed)) next[k] = v == null ? '' : String(v)
  formValues.value = next
}

function beforeUploadFile(file) {
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('文件超过 10MB 上限')
    return false
  }
  return true
}

async function uploadFileFor(file, paramName) {
  try {
    const res = await uploadFile(file)
    fileInputs[paramName] = {
      serverPath: res.path,
      originalName: file.name,
      bytes: file.size
    }
    ElMessage.success(`${paramName} 上传完成`)
  } catch (e) {
    // interceptor already toasted
  }
}

async function openPreview() {
  previewing.value = true
  previewData.value = null
  previewOpen.value = true
  try {
    const params = {}
    for (const [k, v] of Object.entries(formValues.value)) {
      if (v === '' || v == null) continue
      params[k] = String(v)
    }
    previewData.value = await dryRun({
      scriptId: activeScript.value.id,
      tenantId: tenantId.value,
      params,
      presetId: presetId.value
    })
  } catch {
    previewOpen.value = false
  } finally {
    previewing.value = false
  }
}

function collectParams() {
  const out = {}
  for (const [k, v] of Object.entries(formValues.value)) {
    if (v === '' || v == null) continue
    if (fileInputs[k]) { out[k] = fileInputs[k].serverPath; continue }
    out[k] = String(v)
  }
  return out
}

function parseBatchRows() {
  // V3 (PR-8): rows come from the structured editor, not a textarea.
  // If the user enabled "只执行有效行", the editor returns only rows that
  // passed per-row validation; otherwise we send whatever the user typed.
  if (!batchRows.value || !batchRows.value.length) return []
  // The editor exposes validRows() when onlyValid is on.
  const editor = batchEditorRef.value
  if (editor && typeof editor.validRows === 'function') {
    try {
      const valid = editor.validRows()
      return valid.map((r) => {
        const o = {}
        for (const [k, v] of Object.entries(r)) {
          if (v !== '' && v != null) o[k] = String(v)
        }
        return o
      })
    } catch { /* fall through to raw rows */ }
  }
  return batchRows.value
}

function closeDrawer() {
  if (running.value) {
    ElMessage.warning('脚本正在执行，无法关闭')
    return
  }
  drawerOpen.value = false
}

function resetDefaults() {
  formRef.value?.resetToDefaults?.()
}

function backToForm() {
  resultHistory.value = null
  resultStdout.value = ''
  resultStderr.value = ''
}

function goHistory() {
  closeDrawer()
  router.push('/history')
}

// V3 (PR-8): retry a single row in the last batch. Re-runs the original
// params via /batches/{batchId}/retry/{rowIndex}, then refreshes the row
// list so the new executionId + status appear inline. The original row
// stays intact on the server; a fresh ExecutionHistory is linked back via
// parent_execution_id.
async function retryBatchRow(rowIndex) {
  if (!batchResult.value?.batchId) return
  retryingRow.value = rowIndex
  try {
    await apiRetryBatchRow(batchResult.value.batchId, rowIndex, true)
    // Refresh the table so the new executionId replaces the failed one.
    const detailList = await getBatch(batchResult.value.batchId).catch(() => [])
    batchRowsForTable.value = detailList || []
    // Re-derive summary counts from the fresh detail list.
    const succeeded = batchRowsForTable.value.filter((r) => r.success).length
    const failed = batchRowsForTable.value.length - succeeded
    batchResult.value = { ...batchResult.value, succeeded, failed }
    ElMessage.success(`第 ${rowIndex + 1} 行已重新提交`)
  } catch (err) {
    ElMessage.error(`重试失败：${err?.message || '未知错误'}`)
  } finally {
    retryingRow.value = -1
  }
}

async function runScript() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  // V2: DANGEROUS scripts require the user to type CONFIRM. Dry-run / batch /
  // scripted paths do not go through this gate (they call /api/executions/preview
  // or send bypassDangerousCheck via the snapshot re-run flow).
  const riskLevel = normalizeRiskLevel(activeScript.value?.riskLevel)
  let confirmToken = null
  if (riskLevel === RISK_LEVEL.DANGEROUS) {
    try {
      const result = await ElMessageBox.prompt(
        `此脚本属于「危险」级别，操作可能不可恢复或对外部有副作用。\n` +
        `请输入 ${RISK_CONFIRM_TOKEN} 以确认执行：`,
        '危险脚本确认',
        {
          inputPattern: new RegExp(`^${RISK_CONFIRM_TOKEN}$`),
          inputErrorMessage: `必须输入 ${RISK_CONFIRM_TOKEN}`,
          confirmButtonText: '执行',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
      // ElMessageBox returns { value } in element-plus; normalize across versions.
      confirmToken = (result && (result.value || result)) || RISK_CONFIRM_TOKEN
    } catch {
      return  // user cancelled
    }
  }

  running.value = true
  elapsed.value = 0
  if (elapsedTimer) clearInterval(elapsedTimer)
  elapsedTimer = setInterval(() => { elapsed.value += 1 }, 1000)

  const params = collectParams()
  // Persist the last-used params for this script so the next open pre-fills the
  // form. File params are skipped: their value is a server-side path that only
  // exists for one execution, and re-seeding it would silently reuse a stale
  // upload.
  setItem(LAST_TENANT_KEY, tenantId.value)
  const all = getItem(LAST_PARAMS_KEY, {}) || {}
  const persistParams = {}
  for (const [k, v] of Object.entries(params)) {
    if (fileInputs[k]) continue
    persistParams[k] = v
  }
  all[activeScript.value.id] = persistParams
  setItem(LAST_PARAMS_KEY, all)

  // If batch mode, parse rows and call /batches/execute instead.
  if (batchMode.value) {
    try {
      // V3 (PR-8): the editor owns row validation. Run validate first so
      // the user sees inline errors instead of silently losing rows.
      const editor = batchEditorRef.value
      if (editor && typeof editor.validate === 'function') editor.validate()
      const rows = parseBatchRows()
      if (!rows.length) {
        ElMessage.warning(batchInvalidCount.value
          ? `全部 ${batchInvalidCount.value} 行校验失败，无法提交。请修正或勾选「只执行有效行」后再试。`
          : '未解析到任何批次行')
        running.value = false
        return
      }
      if (batchInvalidCount.value && batchRows.value.length !== rows.length) {
        ElMessage.info(`已跳过 ${batchRows.value.length - rows.length} 行无效数据`)
      }
      const sum = await runBatch({
        scriptId: activeScript.value.id,
        tenantId: tenantId.value,
        presetId: presetId.value,
        rows,
        // BatchService fans out to executor.execute(); pass the token so each
        // row inherits the dangerous-script authorisation.
        confirmToken: confirmToken || undefined
      })
      // Refresh details for each row's history id
      batchResult.value = sum
      const detailList = await getBatch(sum.batchId).catch(() => sum.historyIds.map((id) => ({ id, scriptName: activeScript.value.displayName, tenantName: '', success: null, status: '—' })))
      batchRowsForTable.value = detailList || []
      batchResultOpen.value = true
      recentScripts(6).then((r) => { recentList.value = r || [] }).catch(() => {})
    } catch (_) { /* interceptor surfaced */ }
    finally {
      running.value = false
      if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null }
    }
    return
  }

  try {
    const payload = {
      scriptId: activeScript.value.id,
      tenantId: tenantId.value,
      params,
      presetId: presetId.value,
      fileInputs: Object.fromEntries(
        Object.entries(fileInputs).map(([k, v]) => [k, v.serverPath])
      )
    }
    if (confirmToken) payload.confirmToken = confirmToken
    // V3 (PR-2): 走异步执行：早返 executionId，由 executionStore 的全局 tick 驱动状态。
    // 用户可关闭抽屉 / 切换页面，状态会继续在任务中心 + 后台更新；回来时 resultHistory
    // 由下面的 watch 终态后回填。
    const submission = await exec.submit(payload)
    runningExecutionId.value = submission
    startLiveLog()
    const startedAt = Date.now()
    // 不阻塞等待 — 切走 / 关闭抽屉都不影响后端执行。
    // 等终态（或失败 / 超时）后回填 result panel。
    waitForTerminal(submission)
      .then(async (finalView) => {
        try {
          const stateRes = await executionState(submission)
          const full = stateRes?.data || finalView
          resultHistory.value = full
          const [so, se] = await Promise.all([
            readStdout(submission).catch(() => ''),
            readStderr(submission).catch(() => '')
          ])
          resultStdout.value = so || ''
          resultStderr.value = se || ''
          // 把右侧实时日志也固化下来，避免 liveLog 空窗
          liveLog.value = so || liveLog.value
        } catch (_) { /* tolerate */ }
        finally { finalizeRun() }
      })
      .catch((err) => {
        // 超时或网络断开：保留 running 状态，由用户从任务中心继续观察
        console.warn('waitForTerminal failed', err)
        finalizeRun()
      })
    function finalizeRun() {
      running.value = false
      if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null }
      stopLiveLog()
      recentScripts(6).then((r) => { recentList.value = r || [] }).catch(() => {})
    }
    void startedAt
  } catch (_) {
    // axios interceptor already toasted
    running.value = false
    if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null }
    stopLiveLog()
  }
}

async function rerunFromResult() {
  if (!resultHistory.value) return
  // Re-run with the same params from the just-finished history (these are
  // exactly what was persisted to localStorage).
  await runScript()
}

// V3 (PR-2): 取消当前正在运行的执行。executionId 在 submit 时就已经拿到，
// 不再需要按 script+tenant 反查 — 即使用户打开了多个并发任务，也只影响眼前这一个。
async function cancelRunning() {
  if (!running.value || cancelling.value) return
  const id = runningExecutionId.value
  if (!id) {
    // 兜底：异步路径未就绪，仍按旧逻辑查
    const scriptId = activeScript.value?.id
    const tId = tenantId.value
    if (!scriptId || !tId) return
    cancelling.value = true
    try {
      const list = await activeExecutions(scriptId, tId).catch(() => [])
      if (!list || !list.length) {
        ElMessage.info('当前没有正在运行的执行（可能已结束）')
        return
      }
      for (const e of list) {
        try { await cancelExecution(e.id) } catch (_) { /* keep going */ }
      }
      ElMessage.success(`已发送取消信号（${list.length} 个执行）`)
    } catch (_) {
      // axios interceptor already toasted
    } finally {
      cancelling.value = false
    }
    return
  }
  cancelling.value = true
  try {
    await cancelExecution(id)
    ElMessage.success(`已请求取消 #${id}`)
  } catch (_) {
    // axios interceptor already toasted
  } finally {
    cancelling.value = false
  }
}

async function toggleFavorite(s, val) {
  await setScriptFavorite(s.id, val)
  s.favorite = val
}

// Responsive drawer size. Declared at the top level (not inside the async
// onMounted body) so the teardown below is actually registered: a lifecycle
// hook called after an `await` inside onMounted has no live instance and Vue
// silently drops it, leaking the listener and the 1 s elapsed-time interval.
const drawerSize = ref('520px')
function computeDrawerSize() {
  drawerSize.value = window.innerWidth >= 1280 ? '560px' : '460px'
}
// V3 (PR-2): 当前正在运行的 executionId。开启异步路径后，runScript 不再
// 阻塞等待 submit 结果，而是把 executionId 塞回这里，由 executionStore
// 的全局轮询驱动 UI 状态。这样切到别的页面也不会丢进度，回来能直接看到。
// （声明在前面的 setup 区域）

onUnmounted(() => {
  window.removeEventListener('resize', computeDrawerSize)
  window.removeEventListener('resize', checkNarrow)
  if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null }
  if (liveLogTimer) { clearInterval(liveLogTimer); liveLogTimer = null }
  if (nowTimer) { clearInterval(nowTimer); nowTimer = null }
})

onMounted(async () => {
  computeDrawerSize()
  window.addEventListener('resize', computeDrawerSize)
  window.addEventListener('resize', checkNarrow)
  await refreshAll()
  // Consume a possible rerun handoff from HistoryView (session storage)
  await consumeRerun()
})
</script>

<style scoped>
.sb-toolbar { margin-bottom: 16px; }
.sb-search { max-width: 420px; }

.sb-block { margin-bottom: 24px; }

.sb-title-tag { margin-left: 8px; vertical-align: middle; }
.sb-group-title {
  cursor: pointer;
  user-select: none;
  padding: 2px 0;
}

.sb-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

/* 最近使用: thinner rows, not full cards */
.sb-script-card-recent {
  background: var(--sb-bg-panel);
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 10px 12px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 56px;
}
.sb-script-card-recent:hover { border-color: var(--sb-primary); }
.sb-recent-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.sb-recent-name { font-weight: 600; font-size: 13.5px; color: var(--sb-text); }
.sb-recent-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--sb-text-3);
}
.sb-recent-meta .dot { color: var(--sb-text-3); }
.sb-exec-edit-link {
  color: var(--el-color-primary);
  text-decoration: none;
}
.sb-exec-edit-link:hover { text-decoration: underline; }

.sb-drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.sb-drawer-title { font-size: 16px; font-weight: 600; }

.sb-form-section { margin-bottom: 18px; }
.sb-form-section-label {
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 6px;
  color: var(--sb-text);
}

/* V3 (PR-3): 4 来源徽章 + 撤销按钮 */
.sb-draft-badges { padding: 0; }
.sb-draft-row {
  display: flex; flex-wrap: wrap; gap: 6px; align-items: center;
}
.sb-draft-badge { cursor: pointer; }
.sb-draft-hint { font-size: 11px; opacity: 0.75; margin-left: 4px; }
.sb-draft-banner {
  margin-top: 8px;
  display: flex; gap: 6px; align-items: center;
  padding: 6px 10px;
  background: #fff7ed;
  border: 1px solid #fed7aa;
  border-radius: 6px;
  font-size: 12px;
  color: #9a3412;
}

/* V3 (PR-4): 抽屉内左右分栏（宽屏） */
.sb-exec-split {
  display: grid;
  grid-template-columns: 380px 1fr;
  gap: 16px;
  height: 100%;
  min-height: 0;
}
.sb-exec-split.sb-exec-narrow {
  grid-template-columns: 1fr;
  grid-template-rows: minmax(0, 1fr) minmax(0, 1fr);
}
.sb-exec-left { overflow-y: auto; padding-right: 4px; min-height: 0; }
.sb-exec-right {
  display: flex; flex-direction: column;
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  background: #fafbfc;
  min-height: 0;
  overflow: hidden;
}
.sb-exec-right-header {
  font-size: 12px;
  font-weight: 500;
  padding: 8px 12px;
  border-bottom: 1px solid var(--sb-border);
  background: #f1f5f9;
  color: var(--sb-text);
}
.sb-live-log { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.sb-log-stdout {
  flex: 1; min-height: 0;
  background: #0b1220; color: #d6e2ff;
  border-radius: 0; margin: 0;
  padding: 10px 12px;
  font-size: 12px;
  font-family: var(--el-font-family-monospace, monospace);
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.sb-log-error { padding: 8px 12px; }
.sb-right-empty { padding: 14px; font-size: 13px; }

/* V3 (PR-5): 快捷操作 */
.sb-section-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 8px;
}
.sb-qa-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 10px;
}

.sb-file-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px dashed var(--sb-border);
}
.sb-file-row:last-child { border-bottom: 0; }
.sb-file-row-label {
  font-weight: 500;
  min-width: 100px;
}
.sb-file-name {
  color: var(--sb-text-3);
  font-family: var(--sb-mono);
  font-size: 12px;
  flex: 1;
}

.sb-cmd {
  background: #fafafa;
  font-size: 12px;
  word-break: break-all;
  white-space: pre-wrap;
}
.sb-preview-meta {
  margin-top: 12px;
  font-size: 13px;
  color: var(--sb-text-2);
  display: grid;
  gap: 4px;
}
</style>