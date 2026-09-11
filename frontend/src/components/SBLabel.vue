<!--
  SBLabel — a label + question-mark tooltip combo.

  Used inside el-form-item via the #label slot so the user gets a
  consistent pattern across the app: visible field name on the left,
  small "?" icon that reveals a Chinese tooltip on hover.

  Usage:
    <el-form-item>
      <template #label><SBLabel text="参数名" tip="..." required /></template>
      <el-input ... />
    </el-form-item>
-->
<template>
  <span class="sb-label">
    <span class="sb-label-text" :class="{ required }">
      <span v-if="required" class="sb-label-star">*</span>{{ text }}
    </span>
    <el-tooltip
      v-if="tip"
      :content="tip"
      placement="top"
      :show-after="120"
      raw-content
    >
      <el-icon class="sb-label-tip">
        <QuestionFilled />
      </el-icon>
    </el-tooltip>
  </span>
</template>

<script setup>
import { QuestionFilled } from '@element-plus/icons-vue'

defineProps({
  text: { type: String, required: true },
  tip: { type: String, default: '' },
  required: { type: Boolean, default: false }
})
</script>

<style scoped>
.sb-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.sb-label-text {
  color: var(--sb-text);
  font-weight: 500;
}
.sb-label-text.required::before { content: ''; }
.sb-label-star {
  color: var(--el-color-danger);
  margin-right: 2px;
}
.sb-label-tip {
  font-size: 13px;
  color: var(--sb-text-3);
  cursor: help;
  display: inline-flex;
  align-items: center;
  line-height: 1;
}
.sb-label-tip:hover { color: var(--sb-primary); }
</style>