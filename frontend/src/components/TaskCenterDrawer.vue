<template>
  <el-drawer
    :model-value="ui.taskCenterOpen"
    direction="rtl"
    size="420px"
    :with-header="false"
    @update:model-value="(v) => v ? ui.openTaskCenter() : ui.closeTaskCenter()"
  >
    <div class="tc">
      <header class="tc-header">
        <div class="tc-title">
          <el-icon><Timer /></el-icon>
          <span>任务中心</span>
          <el-tag v-if="exec.activeCount" type="warning" size="small" effect="dark">
            运行中 {{ exec.activeCount }}
          </el-tag>
        </div>
        <el-button text @click="ui.closeTaskCenter()">
          <el-icon><Close /></el-icon>
        </el-button>
      </header>

      <section v-if="exec.activeCount === 0 && recent.length === 0" class="tc-empty">
        <el-empty description="暂无任务" :image-size="80" />
      </section>

      <section v-else>
        <div v-if="!exec.networkOnline" class="tc-offline">
          <el-alert type="warning" :closable="false" show-icon>
            连接中断，正在恢复…（已等待 {{ offlineSecs }}s）
          </el-alert>
        </div>

        <h4 v-if="exec.activeCount" class="tc-section">运行中</h4>
        <div
          v-for="view in exec.sortedActive"
          :key="view.id"
          class="tc-item"
          :class="{ 'tc-item-stale': !exec.networkOnline }"
          @click="openHistory(view.id)"
        >
          <div class="tc-item-main">
            <div class="tc-item-row">
              <span class="tc-item-id">#{{ view.id }}</span>
              <el-tag :type="statusTag(view.status)" size="small" effect="light">
                {{ statusText(view.status) }}
              </el-tag>
            </div>
            <div class="tc-item-meta">
              脚本 #{{ view.scriptId }} · 租户 #{{ view.tenantId }} ·
              {{ relTime(view.startedAtMs) }}
            </div>
            <div v-if="view.cancelled" class="tc-item-warn">已请求取消</div>
          </div>
          <el-button
            v-if="!isTerminal(view.status)"
            type="danger"
            size="small"
            text
            @click.stop="onCancel(view.id)"
          >
            取消
          </el-button>
        </div>

        <h4 v-if="recent.length" class="tc-section">最近结束</h4>
        <div
          v-for="view in recent"
          :key="view.id"
          class="tc-item tc-item-finished"
          @click="openHistory(view.id)"
        >
          <div class="tc-item-main">
            <div class="tc-item-row">
              <span class="tc-item-id">#{{ view.id }}</span>
              <el-tag :type="statusTag(view.status)" size="small" effect="plain">
                {{ statusText(view.status) }}
              </el-tag>
            </div>
            <div class="tc-item-meta">
              脚本 #{{ view.scriptId }} · 租户 #{{ view.tenantId }} ·
              {{ relTime(view.finishedAt) }}
            </div>
          </div>
        </div>
      </section>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, Timer } from '@element-plus/icons-vue'
import { useExecutionStore } from '../stores/executionStore'
import { useUiStore } from '../stores/uiStore'
import { STATUS_LABEL } from '../utils/labels'

const exec = useExecutionStore()
const ui = useUiStore()
const router = useRouter()

const recent = computed(() => exec.recentHistory)

const offlineSecs = computed(() => {
  if (exec.networkOnline || !exec.offlineSince) return 0
  return Math.floor((Date.now() - exec.offlineSince) / 1000)
})

onMounted(() => {
  // 进入任务中心时立即拉一次活跃列表，避免首屏空白
  exec.refreshActive()
})

function isTerminal(s) { return exec.isTerminal(s) }
function statusText(s) { return STATUS_LABEL[s] || s || '未知' }
function statusTag(s) {
  return ({
    SUCCESS: 'success', FAILED: 'danger', TIMEOUT: 'warning',
    CANCELLED: 'info', PRECHECK_FAILED: 'danger', INTERRUPTED: 'info',
    PENDING: 'warning', RUNNING: 'warning'
  })[s] || 'info'
}
function relTime(ms) {
  if (!ms) return ''
  const d = Math.floor((Date.now() - ms) / 1000)
  if (d < 60) return `${d}s 前`
  if (d < 3600) return `${Math.floor(d / 60)}m 前`
  if (d < 86400) return `${Math.floor(d / 3600)}h 前`
  return `${Math.floor(d / 86400)}d 前`
}

async function onCancel(id) {
  try {
    await ElMessageBox.confirm(`确定取消执行 #${id}？`, '取消确认', {
      confirmButtonText: '取消执行',
      cancelButtonText: '返回',
      type: 'warning'
    })
    await exec.cancel(id)
    ElMessage.success(`已请求取消 #${id}`)
  } catch (e) {
    // 用户取消
  }
}

function openHistory(id) {
  ui.closeTaskCenter()
  router.push({ name: 'history', query: { id } })
}
</script>

<style scoped>
.tc { padding: 0 16px 16px; height: 100%; display: flex; flex-direction: column; }
.tc-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 0 10px; border-bottom: 1px solid var(--el-border-color-lighter);
}
.tc-title { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 15px; }
.tc-empty { flex: 1; display: flex; align-items: center; justify-content: center; }
.tc-offline { padding: 10px 0; }
.tc-section {
  font-size: 12px; font-weight: 600; color: var(--el-text-color-secondary);
  margin: 16px 0 8px; text-transform: uppercase; letter-spacing: 0.5px;
}
.tc-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 12px; border-radius: 6px; cursor: pointer;
  margin-bottom: 4px; transition: background 0.15s;
}
.tc-item:hover { background: var(--el-fill-color-light); }
.tc-item-stale { opacity: 0.6; }
.tc-item-finished { opacity: 0.85; }
.tc-item-main { flex: 1; min-width: 0; }
.tc-item-row { display: flex; align-items: center; gap: 8px; }
.tc-item-id { font-family: var(--el-font-family-monospace, monospace); font-weight: 600; }
.tc-item-meta { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
.tc-item-warn { font-size: 12px; color: var(--el-color-warning); margin-top: 2px; }
</style>
