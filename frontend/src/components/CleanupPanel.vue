<!--
  CleanupPanel — the Data Cleanup tab content.
  Hosts retention inputs, the "preview" entry point, and the recent
  cleanup history list. The actual preview / confirm / report flows
  live in their own child components.
-->
<template>
  <div class="cleanup-root">
    <!-- Fixed safety notice (per spec section 六) -->
    <div class="safety-notice">
      <div class="safety-title">数据清理</div>
      <p class="safety-text">
        BigData Script Box 不会自动删除任何数据。所有清理操作都必须由用户手动发起，并经过预览和二次确认。
      </p>
    </div>

    <!-- Safety boundary (per spec section 六) -->
    <div class="boundary-card">
      <div class="boundary-head">安全边界</div>
      <p class="boundary-text">数据清理只允许操作 BigData Script Box 自身产生的数据和受控目录。</p>
      <div class="boundary-grid">
        <div>
          <div class="boundary-sub">会删除</div>
          <ul class="boundary-list">
            <li>Execution 目录（stdout / stderr / input / artifact）</li>
            <li>Execution History 行</li>
            <li>Application Log 文件（logback 写入的滚动日志）</li>
          </ul>
        </div>
        <div>
          <div class="boundary-sub">不会删除</div>
          <ul class="boundary-list">
            <li>HDFS / Hive / Hudi / HBase / Spark / Flink / Kafka 数据</li>
            <li>Keytab / Tenant / Script / Script Version / Preset / Scenario / Global Variable</li>
            <li>业务目录、用户任意输入的服务器路径、/opt 下其他应用、系统文件</li>
          </ul>
        </div>
      </div>
    </div>

    <!-- Controlled paths (per spec section 十二) -->
    <div class="controlled-paths">
      <div class="controlled-head">受控目录（由后端配置决定，无法在前端修改）</div>
      <div class="controlled-list">
        <div class="controlled-row">
          <span class="controlled-label">Execution</span>
          <span class="mono">{{ controlled.executionRoot || '—' }}</span>
        </div>
        <div class="controlled-row">
          <span class="controlled-label">Application Log</span>
          <span class="mono">{{ controlled.logsRoot || '（未配置）' }}</span>
        </div>
      </div>
      <div class="muted small" v-if="preview?.warnings?.length">
        <div v-for="(w, i) in preview.warnings" :key="i">⚠ {{ w }}</div>
      </div>
    </div>

    <!-- Retention inputs (per spec section 九) -->
    <div class="sb-card sb-cleanup-card">
      <div class="retention-head">
        <div>
          <div class="retention-title">保留天数</div>
          <p class="muted small">0 表示禁用该类目。修改后点保存设置。</p>
        </div>
      </div>

      <div class="retention-grid">
        <div class="retention-field">
          <label>执行历史保留天数</label>
          <el-input-number v-model="retention.historyDays" :min="0" :max="365" :step="1" />
        </div>
        <div class="retention-field">
          <label>产物文件保留天数</label>
          <el-input-number v-model="retention.artifactDays" :min="0" :max="365" :step="1" />
        </div>
        <div class="retention-field">
          <label>执行目录保留天数</label>
          <el-input-number v-model="retention.executionDays" :min="0" :max="365" :step="1" />
        </div>
        <div class="retention-field">
          <label>应用日志保留天数</label>
          <el-input-number v-model="retention.logDays" :min="0" :max="365" :step="1" />
        </div>
      </div>

      <div class="retention-actions">
        <el-button :loading="savingSettings" @click="onSaveSettings">保存设置</el-button>
      </div>
    </div>

    <!-- Single entry point (per spec section 九): preview only. -->
    <div class="sb-card sb-cleanup-card">
      <div class="entry-row">
        <el-button
          type="primary"
          :loading="previewLoading"
          :icon="View"
          @click="onPreview"
        >预览清理内容</el-button>
        <span class="muted">
          必须先预览，确认无误后再清理。预览 10 分钟内有效，过期需重新预览。
        </span>
      </div>
    </div>

    <!-- Recent cleanup history (per spec section 二十三) -->
    <div class="sb-card sb-cleanup-card">
      <div class="history-head">
        <div class="history-title">最近清理记录</div>
        <el-button text :icon="Refresh" @click="loadHistory">刷新</el-button>
      </div>
      <el-table :data="history" stripe empty-text="暂无清理记录">
        <el-table-column label="时间" min-width="170">
          <template #default="{ row }">
            <span class="mono">{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="resultTagType(row.result)"
              disable-transitions
              effect="plain"
            >{{ resultLabel(row.result) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="删除概览" min-width="220">
          <template #default="{ row }">
            目录 {{ row.executionDeleted }} · 产物 {{ row.artifactDeleted }}
            · 日志 {{ row.logDeleted }} · 历史 {{ row.historyDeleted }}
          </template>
        </el-table-column>
        <el-table-column label="释放" width="110">
          <template #default="{ row }">
            <span class="mono">{{ formatBytes(row.bytesFreed) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">
            <span class="mono">{{ Math.round((row.elapsedMs || 0) / 100) / 10 }}s</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="showHistoryDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- Preview drawer (per spec section 十一-十五) -->
    <CleanupPreviewDrawer
      v-model:open="previewDrawerOpen"
      :preview="preview"
      :controlled="controlled"
      @confirm="openConfirm"
    />

    <!-- Confirm dialog (per spec section 十六-十七): user must type CLEAN. -->
    <CleanupConfirmDialog
      v-model:open="confirmDialogOpen"
      :preview="preview"
      :preview-id="currentPreviewId"
      @done="onExecuteDone"
    />

    <!-- Report drawer (per spec section 二十二) -->
    <CleanupReportDrawer
      v-model:open="reportDrawerOpen"
      :report="lastReport"
    />
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, View } from '@element-plus/icons-vue'
import {
  previewCleanup, getSettings, updateSettings,
  listCleanupHistory
} from '../api/admin'
import { formatBytes, formatTimestamp } from '../utils/format'
import CleanupPreviewDrawer from './CleanupPreviewDrawer.vue'
import CleanupConfirmDialog from './CleanupConfirmDialog.vue'
import CleanupReportDrawer from './CleanupReportDrawer.vue'

const retention = reactive({ historyDays: 30, artifactDays: 30, executionDays: 30, logDays: 7 })
const controlled = reactive({ executionRoot: '', logsRoot: '' })
const preview = ref(null)
const currentPreviewId = ref('')
const savingSettings = ref(false)
const previewLoading = ref(false)
const previewDrawerOpen = ref(false)
const confirmDialogOpen = ref(false)
const reportDrawerOpen = ref(false)
const lastReport = ref(null)
const history = ref([])

const formatTime = formatTimestamp

function resultLabel(r) {
  if (r === 'SUCCESS') return '成功'
  if (r === 'PARTIAL') return '部分失败'
  if (r === 'FAILED')  return '失败'
  return r || '—'
}
function resultTagType(r) {
  if (r === 'SUCCESS') return 'success'
  if (r === 'PARTIAL') return 'warning'
  return 'danger'
}

async function loadRetentionFromSettings() {
  try {
    const raw = await getSettings()
    const map = raw?.data || raw || {}
    if (typeof map['cleanup.historyDays']   !== 'undefined') retention.historyDays   = parseInt(map['cleanup.historyDays'],   10)
    if (typeof map['cleanup.artifactDays']  !== 'undefined') retention.artifactDays  = parseInt(map['cleanup.artifactDays'],  10)
    if (typeof map['cleanup.executionDays'] !== 'undefined') retention.executionDays = parseInt(map['cleanup.executionDays'], 10)
    if (typeof map['cleanup.logDays']       !== 'undefined') retention.logDays       = parseInt(map['cleanup.logDays'],       10)
  } catch (_) {
    // tolerate 404 / no rows yet — fall back to defaults.
  }
}

async function onSaveSettings() {
  savingSettings.value = true
  try {
    await updateSettings({
      'cleanup.historyDays':   String(retention.historyDays),
      'cleanup.artifactDays':  String(retention.artifactDays),
      'cleanup.executionDays': String(retention.executionDays),
      'cleanup.logDays':       String(retention.logDays)
    })
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error('保存失败: ' + (e?.message || e))
  } finally {
    savingSettings.value = false
  }
}

async function onPreview() {
  previewLoading.value = true
  try {
    // Persist the retention values first so the preview reflects them.
    await updateSettings({
      'cleanup.historyDays':   String(retention.historyDays),
      'cleanup.artifactDays':  String(retention.artifactDays),
      'cleanup.executionDays': String(retention.executionDays),
      'cleanup.logDays':       String(retention.logDays)
    })
    const r = await previewCleanup({
      historyDays:   retention.historyDays,
      artifactDays:  retention.artifactDays,
      executionDays: retention.executionDays,
      logDays:       retention.logDays
    })
    const data = r?.data || r || null
    if (!data || r?.code !== 0) {
      ElMessage.error('预览失败: ' + (r?.message || '未知错误'))
      return
    }
    preview.value = data
    currentPreviewId.value = data.previewId || ''
    // Apply controlled paths from preview response (server-fixed).
    if (data.controlledPaths) {
      controlled.executionRoot = data.controlledPaths.executionsRoot || ''
      controlled.logsRoot      = data.controlledPaths.logsRoot || ''
    }
    previewDrawerOpen.value = true
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || String(e)
    if (msg.includes('PREVIEW_EXPIRED')) {
      ElMessage.error('预览已过期，请重新预览')
    } else {
      ElMessage.error('预览失败: ' + msg)
    }
  } finally {
    previewLoading.value = false
  }
}

function openConfirm() {
  previewDrawerOpen.value = false
  confirmDialogOpen.value = true
}

async function onExecuteDone(report) {
  confirmDialogOpen.value = false
  lastReport.value = report
  preview.value = null
  currentPreviewId.value = ''
  reportDrawerOpen.value = true
  await loadHistory()
}

async function loadHistory() {
  try {
    const r = await listCleanupHistory(20)
    history.value = r?.data || r || []
  } catch (_) {
    history.value = []
  }
}

async function showHistoryDetail(row) {
  const lines = []
  lines.push(`时间: ${formatTime(row.createdAt)}`)
  lines.push(`结果: ${resultLabel(row.result)}`)
  lines.push(`预览ID: ${row.previewId || '—'}`)
  lines.push(`保留天数: ${row.retentionJson || '—'}`)
  lines.push(`执行目录: ${row.executionDeleted}`)
  lines.push(`产物: ${row.artifactDeleted}`)
  lines.push(`日志: ${row.logDeleted}`)
  lines.push(`历史: ${row.historyDeleted}`)
  lines.push(`释放: ${formatBytes(row.bytesFreed)}`)
  lines.push(`跳过: ${row.skippedCount}`)
  lines.push(`失败: ${row.failedCount}`)
  if (row.message) lines.push(`\n${row.message}`)
  await ElMessageBox.alert(lines.join('\n'), '清理详情', { confirmButtonText: '关闭' })
}

onMounted(async () => {
  await loadRetentionFromSettings()
  await loadHistory()
})
</script>

<style scoped>
.cleanup-root { display: flex; flex-direction: column; gap: 16px; }

.safety-notice {
  background: #fff7ed;
  border: 1px solid #fed7aa;
  border-radius: 6px;
  padding: 14px 18px;
}
.safety-title {
  font-weight: 600;
  color: #9a3412;
  margin-bottom: 6px;
  font-size: 14px;
}
.safety-text { margin: 0; color: #7c2d12; font-size: 13px; line-height: 1.6; }

.boundary-card {
  background: var(--sb-bg-panel);
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 14px 18px;
}
.boundary-head { font-weight: 600; margin-bottom: 6px; font-size: 13.5px; }
.boundary-text { margin: 0 0 10px 0; color: var(--sb-text-2); font-size: 13px; line-height: 1.6; }
.boundary-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
@media (max-width: 720px) {
  .boundary-grid { grid-template-columns: 1fr; }
}
.boundary-sub { font-weight: 600; font-size: 12.5px; margin-bottom: 4px; }
.boundary-list { margin: 0; padding-left: 20px; font-size: 12.5px; color: var(--sb-text-2); line-height: 1.7; }

.controlled-paths {
  background: var(--sb-bg-panel);
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 14px 18px;
}
.controlled-head {
  font-weight: 600;
  margin-bottom: 8px;
  font-size: 13.5px;
  color: var(--sb-text-1);
}
.controlled-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 6px;
}
.controlled-row {
  display: flex;
  gap: 8px;
  align-items: baseline;
}
.controlled-label {
  font-size: 12px;
  color: var(--sb-text-2);
  min-width: 110px;
}

.sb-cleanup-card { padding: 16px 18px; }

.retention-head { margin-bottom: 12px; }
.retention-title { font-weight: 600; font-size: 14px; }
.retention-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
  margin-bottom: 12px;
}
.retention-field { display: flex; flex-direction: column; gap: 4px; }
.retention-field label { font-size: 12.5px; color: var(--sb-text-2); }
.retention-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  border-top: 1px dashed var(--sb-border);
  padding-top: 12px;
}

.entry-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.history-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.history-title { font-weight: 600; font-size: 14px; }

.muted { color: var(--sb-text-muted); font-size: 12.5px; }
.small { font-size: 12.5px; }
.mono { font-family: var(--sb-mono); font-size: 12.5px; color: var(--sb-text-1); }
</style>