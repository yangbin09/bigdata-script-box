<!--
  CleanupPreviewDrawer — shows the candidate breakdown from the backend
  preview. Pure read; nothing has been deleted yet. The "确认清理" button
  emits 'confirm' which the parent uses to open the typed-token dialog.
-->
<template>
  <el-drawer
    :model-value="open"
    direction="rtl"
    size="840px"
    :show-close="false"
    :destroy-on-close="false"
    class="sb-exec-drawer"
    @update:model-value="onClose"
  >
    <template #header>
      <div class="drawer-head">
        <div>
          <div class="drawer-title">清理预览</div>
          <div class="drawer-sub">当前只是预览，没有删除任何文件。</div>
        </div>
        <el-button text :icon="Close" @click="onClose(false)">关闭</el-button>
      </div>
    </template>

    <div v-if="!preview" class="empty">
      <p class="muted">无预览数据</p>
    </div>

    <div v-else class="preview-body">
      <!-- Top stats -->
      <div class="stats-grid">
        <div class="stat">
          <div class="stat-label">执行目录</div>
          <div class="stat-value">{{ totals.executionDirCount }}</div>
          <div class="stat-sub">{{ fmtBytes(totals.executionBytes) }}</div>
        </div>
        <div class="stat">
          <div class="stat-label">产物文件</div>
          <div class="stat-value">{{ totals.artifactCount }}</div>
          <div class="stat-sub">{{ fmtBytes(totals.artifactBytes) }}</div>
        </div>
        <div class="stat">
          <div class="stat-label">应用日志</div>
          <div class="stat-value">{{ totals.logCount }}</div>
          <div class="stat-sub">{{ fmtBytes(totals.logBytes) }}</div>
        </div>
        <div class="stat">
          <div class="stat-label">执行历史</div>
          <div class="stat-value">{{ totals.historyCount }}</div>
          <div class="stat-sub muted">行</div>
        </div>
      </div>

      <div class="total-block">
        <div>
          <div class="muted small">预计释放</div>
          <div class="total-bytes">{{ fmtBytes(totals.totalBytes) }}</div>
        </div>
        <div v-if="totals.skippedRunning > 0" class="skipped-tag">
          <el-tag type="warning" effect="plain" disable-transitions>
            {{ totals.skippedRunning }} 个运行中的执行将被跳过
          </el-tag>
        </div>
      </div>

      <!-- Controlled paths again -->
      <div class="paths-card" v-if="controlled">
        <div class="paths-head">受控目录</div>
        <div class="paths-list">
          <div><span class="muted">Execution</span><span class="mono">{{ controlled.executionRoot || '—' }}</span></div>
          <div><span class="muted">Application Log</span><span class="mono">{{ controlled.logsRoot || '（未配置）' }}</span></div>
        </div>
      </div>

      <!-- Per-category breakdown -->
      <el-empty
        v-if="isEmpty"
        description="按当前保留天数没有任何内容需要清理。无需执行操作。"
      />
      <template v-else>
      <div class="cat">
        <div class="cat-head">
          <div>
            <div class="cat-title">执行数据</div>
            <div class="muted small">条件：{{ preview.executionDays }} 天以前（基于目录 mtime）</div>
          </div>
          <div class="cat-stats">
            <span>数量 <strong>{{ totals.executionDirCount }}</strong></span>
            <span>大小 <strong>{{ fmtBytes(totals.executionBytes) }}</strong></span>
          </div>
        </div>
        <div class="cat-actions">
          <el-button text size="small" @click="openList('executionDirs')">查看全部 ({{ preview.executionDirs.length }})</el-button>
        </div>
        <el-table :data="preview.executionDirs.slice(0, 5)" size="small" empty-text="无候选">
          <el-table-column label="执行编号" prop="id" width="100" />
          <el-table-column label="脚本" prop="scriptName" min-width="120" />
          <el-table-column label="租户" prop="tenantName" min-width="100" />
          <el-table-column label="开始时间" min-width="160">
            <template #default="{ row }">{{ formatTime(row.startTimeIso) }}</template>
          </el-table-column>
          <el-table-column label="状态" prop="status" width="80" />
          <el-table-column label="大小" width="80">
            <template #default="{ row }">{{ fmtBytes(row.sizeBytes) }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="160">
            <template #default="{ row }">
              <el-tag v-if="row.flag && row.flag !== 'CANDIDATE'" size="small" type="warning" effect="plain" disable-transitions>
                跳过：{{ row.reason || row.flag }}
              </el-tag>
              <span v-else class="mono small">{{ row.path }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="cat">
        <div class="cat-head">
          <div>
            <div class="cat-title">产物文件</div>
            <div class="muted small">条件：{{ preview.artifactDays }} 天以前（与执行目录合并扫描，零散孤儿文件单独列出）</div>
          </div>
          <div class="cat-stats">
            <span>数量 <strong>{{ totals.artifactCount }}</strong></span>
            <span>大小 <strong>{{ fmtBytes(totals.artifactBytes) }}</strong></span>
          </div>
        </div>
        <div class="cat-actions">
          <el-button text size="small" @click="openList('artifacts')">查看全部 ({{ preview.artifacts.length }})</el-button>
        </div>
        <el-table :data="preview.artifacts.slice(0, 5)" size="small" empty-text="无候选">
          <el-table-column label="编号" prop="id" width="80" />
          <el-table-column label="路径" min-width="280">
            <template #default="{ row }"><span class="mono small">{{ row.path }}</span></template>
          </el-table-column>
          <el-table-column label="大小" width="100">
            <template #default="{ row }">{{ fmtBytes(row.sizeBytes) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="cat">
        <div class="cat-head">
          <div>
            <div class="cat-title">应用日志</div>
            <div class="muted small">条件：{{ preview.logDays }} 天以前（logback 写入的滚动日志）</div>
          </div>
          <div class="cat-stats">
            <span>数量 <strong>{{ totals.logCount }}</strong></span>
            <span>大小 <strong>{{ fmtBytes(totals.logBytes) }}</strong></span>
          </div>
        </div>
        <div class="cat-actions">
          <el-button text size="small" @click="openList('logs')">查看全部 ({{ preview.logs.length }})</el-button>
        </div>
        <el-table :data="preview.logs.slice(0, 5)" size="small" empty-text="无候选">
          <el-table-column label="路径" min-width="400">
            <template #default="{ row }"><span class="mono small">{{ row.path }}</span></template>
          </el-table-column>
          <el-table-column label="大小" width="100">
            <template #default="{ row }">{{ fmtBytes(row.sizeBytes) }}</template>
          </el-table-column>
          <el-table-column label="修改时间" min-width="160">
            <template #default="{ row }">{{ formatTime(row.mtimeMs) }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="cat">
        <div class="cat-head">
          <div>
            <div class="cat-title">执行历史</div>
            <div class="muted small">条件：{{ preview.historyDays }} 天以前（删除后无法从执行历史查询）</div>
          </div>
          <div class="cat-stats">
            <span>数量 <strong>{{ totals.historyCount }}</strong></span>
          </div>
        </div>
        <div class="cat-actions">
          <el-button text size="small" @click="openList('histories')">查看全部 ({{ preview.histories.length }})</el-button>
        </div>
        <el-table :data="preview.histories.slice(0, 5)" size="small" empty-text="无候选">
          <el-table-column label="编号" prop="id" width="80" />
          <el-table-column label="脚本" prop="scriptName" min-width="120" />
          <el-table-column label="租户" prop="tenantName" min-width="100" />
          <el-table-column label="开始时间" min-width="160">
            <template #default="{ row }">{{ formatTime(row.startTimeIso) }}</template>
          </el-table-column>
          <el-table-column label="状态" prop="status" width="80" />
          <el-table-column label="备注" min-width="160">
            <template #default="{ row }">
              <el-tag v-if="row.flag && row.flag !== 'CANDIDATE'" size="small" type="warning" effect="plain" disable-transitions>
                跳过：{{ row.reason || row.flag }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
      </template>
    </div>

    <!-- Detail list sub-drawer -->
    <el-drawer
      v-model="listDrawerOpen"
      direction="rtl"
      size="640px"
      :show-close="true"
      :destroy-on-close="true"
      class="sb-exec-drawer"
      :title="listTitle"
    >
      <el-table :data="listData" size="small" max-height="100%">
        <el-table-column v-if="currentList === 'executionDirs' || currentList === 'histories'" label="编号" prop="id" width="80" />
        <el-table-column v-if="currentList === 'executionDirs' || currentList === 'histories'" label="脚本" prop="scriptName" min-width="120" />
        <el-table-column v-if="currentList === 'executionDirs' || currentList === 'histories'" label="租户" prop="tenantName" min-width="100" />
        <el-table-column v-if="currentList === 'executionDirs' || currentList === 'histories'" label="开始时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.startTimeIso) }}</template>
        </el-table-column>
        <el-table-column v-if="currentList === 'executionDirs' || currentList === 'histories'" label="状态" prop="status" width="80" />
        <el-table-column label="路径 / 备注" min-width="320">
          <template #default="{ row }">
            <span v-if="row.flag && row.flag !== 'CANDIDATE'" class="skip-line">跳过：{{ row.reason || row.flag }}</span>
            <span v-else class="mono small">{{ row.path }}</span>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ fmtBytes(row.sizeBytes) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <template #footer>
      <div class="sb-exec-footer">
        <el-button @click="onClose(false)">取消</el-button>
        <el-button type="primary" @click="$emit('confirm')">确认清理</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Close } from '@element-plus/icons-vue'
import { formatBytes, formatTimestamp } from '../utils/format'

const props = defineProps({
  open: { type: Boolean, default: false },
  preview: { type: Object, default: null },
  controlled: { type: Object, default: () => ({ executionRoot: '', logsRoot: '' }) }
})
const emit = defineEmits(['update:open', 'confirm'])

function onClose(v) { emit('update:open', v) }

const totals = computed(() => props.preview?.totals || {
  executionDirCount: 0, artifactCount: 0, logCount: 0, historyCount: 0,
  executionBytes: 0, artifactBytes: 0, logBytes: 0, totalBytes: 0,
  skippedRunning: 0
})

// True when the preview found nothing to delete across every category.
// Used by the body to swap the candidate tables for a single friendly hint
// instead of four empty tables that look like a load failure.
const isEmpty = computed(() => {
  const t = totals.value
  return t.executionDirCount === 0 && t.artifactCount === 0 &&
    t.logCount === 0 && t.historyCount === 0 && t.skippedRunning === 0
})

const listDrawerOpen = ref(false)
const currentList = ref('')
const listTitle = computed(() => ({
  executionDirs: '执行目录 — 全部候选项',
  artifacts:     '产物文件 — 全部候选项',
  logs:          '应用日志 — 全部候选项',
  histories:     '执行历史 — 全部候选项'
}[currentList.value] || '候选项'))
const listData = computed(() => {
  if (!props.preview) return []
  const key = currentList.value
  return props.preview[key] || []
})

function openList(key) {
  currentList.value = key
  listDrawerOpen.value = true
}

// Local aliases keep the template tidy; the implementations live in
// src/utils/format.js so byte/time rendering stays consistent across pages.
const fmtBytes = formatBytes
const formatTime = formatTimestamp
</script>

<style scoped>
.drawer-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; width: 100%; }
.drawer-title { font-weight: 600; font-size: 16px; }
.drawer-sub { color: var(--sb-text-muted); font-size: 12.5px; margin-top: 2px; }

.empty { padding: 40px; text-align: center; }
.preview-body { display: flex; flex-direction: column; gap: 18px; padding-bottom: 16px; }

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
@media (max-width: 720px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
}
.stat {
  background: var(--sb-bg-panel);
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 12px 14px;
}
.stat-label { font-size: 12px; color: var(--sb-text-2); margin-bottom: 6px; }
.stat-value { font-size: 22px; font-weight: 600; color: var(--sb-text-1); }
.stat-sub { font-size: 12px; color: var(--sb-text-muted); margin-top: 2px; }

.total-block {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: #f8fafc;
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 12px 16px;
}
.total-bytes { font-size: 22px; font-weight: 600; color: var(--sb-text-1); margin-top: 4px; }

.paths-card {
  background: var(--sb-bg-panel);
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 10px 14px;
}
.paths-head { font-weight: 600; font-size: 13px; margin-bottom: 4px; }
.paths-list { display: flex; flex-direction: column; gap: 4px; font-size: 12.5px; }
.paths-list > div { display: flex; gap: 8px; align-items: baseline; }

.cat {
  background: var(--sb-bg-panel);
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 12px 14px;
}
.cat-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 8px;
}
.cat-title { font-weight: 600; font-size: 13.5px; }
.cat-stats { display: flex; gap: 12px; font-size: 12.5px; color: var(--sb-text-2); }
.cat-stats strong { color: var(--sb-text-1); margin-left: 4px; }
.cat-actions { margin-bottom: 6px; }

.skip-line { color: #b45309; font-size: 12px; }
.mono { font-family: var(--sb-mono); font-size: 12.5px; }
.small { font-size: 12px; }
.muted { color: var(--sb-text-muted); }
</style>