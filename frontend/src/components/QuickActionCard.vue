<!--
  QuickActionCard — 单张快捷操作卡片。

  Behavior:
    - 单击 → open drawer via @click.emit('open', qa)
    - 右侧 ⋯ 菜单：编辑 / 直接执行 / 删除
    - "直接执行" emit('run', qa)
    - 脚本或租户停用 → 卡片底部禁用提示
-->
<template>
  <div
    class="sb-qa-card"
    :class="{ 'sb-qa-disabled': disabled }"
    @click="onOpen"
  >
    <div class="sb-qa-icon">{{ qa.icon || '⚡' }}</div>
    <div class="sb-qa-main">
      <div class="sb-qa-name">{{ qa.name }}</div>
      <div class="sb-qa-meta">
        脚本 #{{ qa.scriptId }} · 租户 #{{ qa.tenantId }}
      </div>
      <div v-if="disabled" class="sb-qa-warn">{{ disabledReason }}</div>
    </div>
    <el-dropdown trigger="click" @command="onCmd" @click.stop>
      <el-button text size="small" @click.stop>
        <el-icon><MoreFilled /></el-icon>
      </el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="edit">编辑</el-dropdown-item>
          <el-dropdown-item command="run" :disabled="disabled">直接执行</el-dropdown-item>
          <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup>
import { MoreFilled } from '@element-plus/icons-vue'

const props = defineProps({
  qa: { type: Object, required: true },
  /** Optional: resolved script (to show display name / detect disabled). */
  script: { type: Object, default: null },
  /** Optional: resolved tenant. */
  tenant: { type: Object, default: null }
})

const emit = defineEmits(['open', 'edit', 'run', 'delete'])

const disabled = computed(() => {
  if (props.script && props.script.enabled === false) return true
  if (props.tenant && props.tenant.enabled === false) return true
  return false
})

const disabledReason = computed(() => {
  if (props.script && props.script.enabled === false) return '脚本已禁用'
  if (props.tenant && props.tenant.enabled === false) return '租户已禁用'
  return '不可用'
})

function onOpen() { emit('open', props.qa) }
function onCmd(cmd) {
  if (cmd === 'edit') emit('edit', props.qa)
  else if (cmd === 'run') emit('run', props.qa)
  else if (cmd === 'delete') emit('delete', props.qa)
}
</script>

<style scoped>
.sb-qa-card {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.sb-qa-card:hover { border-color: var(--el-color-primary); box-shadow: 0 0 0 2px rgba(64,158,255,0.1); }
.sb-qa-disabled { opacity: 0.6; cursor: not-allowed; }
.sb-qa-icon { font-size: 24px; line-height: 1; flex: 0 0 auto; }
.sb-qa-main { flex: 1; min-width: 0; }
.sb-qa-name { font-weight: 600; font-size: 14px; }
.sb-qa-meta { font-size: 12px; color: var(--sb-text-2); margin-top: 2px; }
.sb-qa-warn { font-size: 11px; color: var(--el-color-danger); margin-top: 2px; }
</style>