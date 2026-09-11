<!--
  HistoryView — recent executions table + detail dialog.
  Detail dialog reuses the status-card + tabs layout for consistency.
-->
<template>
  <div>
    <div class="sb-header">
      <div>
        <h2 class="sb-page-title">执行历史</h2>
        <p class="sb-page-sub">最近 {{ rows.length }} 条执行记录（默认 100 条上限）。</p>
      </div>
      <el-button :icon="Refresh" plain @click="refresh" :loading="loading">刷新</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" class="sb-card" stripe>
      <el-table-column prop="id" label="#" width="70" />
      <el-table-column prop="scriptName" label="脚本" min-width="160">
        <template #default="{ row }">
          <span class="mono">{{ row.scriptName }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="tenantName" label="租户" min-width="120" />
      <el-table-column label="结果" width="90" align="center">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.success ? 'success' : 'danger'"
            disable-transitions
          >{{ row.success ? 'ok' : 'fail' }}</el-tag>
          <el-tag
            v-if="row.timeout"
            size="small"
            type="warning"
            disable-transitions
            style="margin-left: 4px"
          >timeout</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="exitCode" label="exit" width="70" align="right" />
      <el-table-column label="耗时" width="100" align="right">
        <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
      </el-table-column>
      <el-table-column label="开始时间" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.startTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="detailOpen"
      :title="`执行详情 #${current?.id ?? ''}`"
      width="780px"
      destroy-on-close
    >
      <template v-if="current">
        <div class="sb-status-card" :class="current.success ? 'ok' : 'fail'">
          <div class="sb-status-icon">
            <el-icon :size="28">
              <component :is="current.success ? CircleCheck : CircleClose" />
            </el-icon>
          </div>
          <div class="sb-status-text">
            <div class="sb-status-headline">{{ current.success ? '成功' : '失败' }}</div>
            <div class="sb-status-sub">
              <span>{{ current.scriptName }}</span>
              <span class="dot">·</span>
              <span>{{ current.tenantName }}</span>
              <span class="dot">·</span>
              <span>{{ formatDuration(current.durationMs) }}</span>
              <span class="dot">·</span>
              <span>{{ formatDateTime(current.startTime) }}</span>
            </div>
          </div>
          <div class="sb-status-tags">
            <el-tag size="small" disable-transitions
              :type="current.success ? 'success' : 'danger'"
            >exit {{ current.exitCode ?? '-' }}</el-tag>
          </div>
        </div>

        <el-tabs v-model="tab">
          <el-tab-pane label="stdout" name="stdout">
            <pre class="sb-log" v-if="stdout">{{ stdout }}</pre>
            <div v-else class="sb-log sb-log-empty">(空)</div>
          </el-tab-pane>
          <el-tab-pane label="stderr" name="stderr">
            <pre class="sb-log" v-if="stderr">{{ stderr }}</pre>
            <div v-else class="sb-log sb-log-empty">(空)</div>
          </el-tab-pane>
          <el-tab-pane label="参数" name="params">
            <pre class="sb-log">{{ paramsPretty }}</pre>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { Refresh, CircleCheck, CircleClose } from '@element-plus/icons-vue'
import { listHistory, getHistory } from '../api/history'
import { readStdout, readStderr } from '../api/executions'
import { formatDateTime, formatDuration, parseParamsJson } from '../utils/format'

const rows = ref([])
const loading = ref(false)

const detailOpen = ref(false)
const current = ref(null)
const stdout = ref('')
const stderr = ref('')
const tab = ref('stdout')

async function refresh() {
  loading.value = true
  try { rows.value = (await listHistory(200)) || [] }
  finally { loading.value = false }
}

async function openDetail(row) {
  current.value = row
  stdout.value = ''
  stderr.value = ''
  tab.value = row.success ? 'stdout' : 'stderr'
  detailOpen.value = true
  try {
    const fresh = await getHistory(row.id)
    current.value = fresh
  } catch { /* keep original row */ }
  try { stdout.value = (await readStdout(row.id)) || '' } catch {}
  try { stderr.value = (await readStderr(row.id)) || '' } catch {}
}

const paramsPretty = () => {
  const p = parseParamsJson(current.value?.parametersJson)
  return Object.keys(p).length ? JSON.stringify(p, null, 2) : '(无参数)'
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

.sb-status-card {
  display: flex; align-items: center; gap: 14px;
  padding: 14px 16px;
  border-radius: 6px;
  border: 1px solid var(--sb-border);
  background: #f8fafc;
  margin-bottom: 12px;
}
.sb-status-card.ok   { background: #f0fdf4; border-color: #bbf7d0; }
.sb-status-card.fail { background: #fef2f2; border-color: #fecaca; }

.sb-status-icon { display: flex; }
.sb-status-card.ok   .sb-status-icon { color: var(--sb-success); }
.sb-status-card.fail .sb-status-icon { color: var(--sb-danger); }

.sb-status-text { flex: 1; }
.sb-status-headline { font-size: 15px; font-weight: 600; }
.sb-status-sub {
  font-size: 12.5px; color: var(--sb-text-3); margin-top: 2px;
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
}
.sb-status-sub .dot { color: var(--sb-text-3); }

.sb-status-tags { display: flex; gap: 4px; }
</style>