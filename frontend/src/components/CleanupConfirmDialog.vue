<!--
  CleanupConfirmDialog — second gate (per spec 十七). They have to type
  the literal token "CLEAN" before the confirm button enables. The
  'done' event emits the report on success.
-->
<template>
  <el-dialog
    :model-value="open"
    width="480px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    @update:model-value="onClose"
  >
    <template #header>
      <div class="dlg-head">
        <div>
          <div class="dlg-title">确认执行数据清理？</div>
          <div class="dlg-sub">本操作不可撤销。</div>
        </div>
      </div>
    </template>

    <div class="dlg-body">
      <div class="summary">
        <div class="row"><span>预计删除文件 / 目录</span><strong>{{ totals.totalFiles }}</strong></div>
        <div class="row"><span>预计删除历史记录</span><strong>{{ totals.historyCount }}</strong></div>
        <div class="row"><span>预计释放空间</span><strong>{{ fmtBytes(totals.totalBytes) }}</strong></div>
        <div v-if="totals.skippedRunning > 0" class="row">
          <span>跳过的运行中任务</span><strong>{{ totals.skippedRunning }}</strong>
        </div>
      </div>

      <div class="warn">
        本操作不可撤销。请输入 <code>CLEAN</code> 确认。
      </div>

      <el-input
        v-model="token"
        placeholder='输入 CLEAN'
        :disabled="loading"
        @input="onTokenChange"
      />
    </div>

    <template #footer>
      <el-button :disabled="loading" @click="onClose(false)">取消</el-button>
      <el-button
        type="danger"
        :disabled="!canConfirm"
        :loading="loading"
        @click="onConfirm"
      >确认清理</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { executeCleanup } from '../api/admin'
import { formatBytes } from '../utils/format'

const props = defineProps({
  open: { type: Boolean, default: false },
  preview: { type: Object, default: null },
  previewId: { type: String, default: '' }
})
const emit = defineEmits(['update:open', 'done'])

const token = ref('')
const loading = ref(false)
const CONFIRM_TOKEN = 'CLEAN'

const canConfirm = computed(() => token.value === CONFIRM_TOKEN && !loading.value)

const totals = computed(() => {
  const t = props.preview?.totals || {}
  return {
    totalFiles: (t.executionDirCount || 0) + (t.artifactCount || 0) + (t.logCount || 0),
    historyCount: t.historyCount || 0,
    totalBytes: t.totalBytes || 0,
    skippedRunning: t.skippedRunning || 0
  }
})

watch(() => props.open, (v) => {
  if (v) token.value = ''
})

function onTokenChange() { /* reactive via computed */ }

async function onConfirm() {
  if (!canConfirm.value) return
  if (!props.previewId) {
    ElMessage.error('预览已过期，请重新预览')
    emit('update:open', false)
    return
  }
  loading.value = true
  try {
    const r = await executeCleanup({
      previewId: props.previewId,
      confirmToken: CONFIRM_TOKEN
    })
    if (r?.code !== 0) {
      const msg = r?.message || '执行失败'
      if (msg.includes('PREVIEW_EXPIRED')) {
        ElMessage.error('预览已过期，请重新预览')
      } else if (msg.includes('confirmation token')) {
        ElMessage.error('确认 token 不正确')
      } else {
        ElMessage.error(msg)
      }
      emit('update:open', false)
      return
    }
    emit('done', r.data || null)
    emit('update:open', false)
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || String(e)
    if (msg.includes('PREVIEW_EXPIRED')) {
      ElMessage.error('预览已过期，请重新预览')
    } else {
      ElMessage.error('执行失败: ' + msg)
    }
    emit('update:open', false)
  } finally {
    loading.value = false
  }
}

function onClose(v) { emit('update:open', v) }

const fmtBytes = formatBytes
</script>

<style scoped>
.dlg-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; width: 100%; }
.dlg-title { font-weight: 600; font-size: 16px; }
.dlg-sub { color: #b91c1c; font-size: 12.5px; margin-top: 2px; }

.dlg-body { display: flex; flex-direction: column; gap: 14px; }

.summary {
  background: var(--sb-bg-panel);
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 12px 14px;
}
.summary .row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  padding: 4px 0;
  color: var(--sb-text-2);
}
.summary .row strong { color: var(--sb-text-1); }

.warn {
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #991b1b;
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
}
.warn code {
  background: #fff;
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid #fca5a5;
  font-family: var(--sb-mono);
  font-weight: 600;
}
</style>