<!--
  ScriptCard — the execution-center tile.
  Layout:
    header:  [display name] ........... [star]
    body:    description (1-2 lines)
    footer:  category · timeout
    action:  hover-only "执行 →" hint at bottom-right
  Disabled scripts are not rendered in ExecuteView, but if used elsewhere they show as disabled.
-->
<template>
  <div
    class="sb-script-card"
    :class="{ disabled: !script.enabled }"
    @click="$emit('click')"
  >
    <div class="sb-script-card-head">
      <div class="sb-script-name">{{ script.displayName || script.name }}</div>
      <button
        class="sb-star"
        :class="{ active: script.favorite }"
        @click.stop="$emit('toggle-favorite', !script.favorite)"
        :title="script.favorite ? '取消收藏' : '收藏'"
      >
        <el-icon :size="16"><component :is="script.favorite ? StarFilled : Star" /></el-icon>
      </button>
    </div>
    <div class="sb-script-desc">{{ script.description || '—' }}</div>
    <div class="sb-script-meta">
      <span class="sb-cat">{{ script.category || '默认' }}</span>
      <span class="dot">·</span>
      <span><el-icon><Timer /></el-icon> {{ script.timeoutSeconds || 600 }}s</span>
      <!-- 「跑上次」：用历史里那套成功参数直接重跑，连抽屉都不用开 -->
      <el-button
        class="sb-rerun-hint"
        size="small" link type="primary"
        @click.stop="$emit('rerun')"
      >跑上次</el-button>
      <span class="sb-run-hint">执行 →</span>
    </div>
  </div>
</template>

<script setup>
import { Star, StarFilled, Timer } from '@element-plus/icons-vue'

defineProps({
  script: { type: Object, required: true }
})

defineEmits(['click', 'toggle-favorite', 'rerun'])
</script>

<style scoped>
.sb-script-card {
  background: var(--sb-bg-panel);
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 14px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 132px;
  transition: none;
  position: relative;
}
.sb-script-card:hover {
  border-color: var(--sb-primary);
  background: #fafcff;
}
.sb-script-card.disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.sb-script-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}
.sb-script-name {
  font-weight: 600;
  font-size: 14.5px;
  color: var(--sb-text);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  line-height: 1.35;
}

.sb-star {
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 2px;
  border-radius: 4px;
  color: var(--sb-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: none;
}
.sb-star:hover { background: #f3f4f6; color: var(--sb-text-2); }
.sb-star.active { color: #f59e0b; }

.sb-script-desc {
  font-size: 12.5px;
  color: var(--sb-text-2);
  line-height: 1.5;
  flex: 1;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.sb-script-meta {
  display: flex;
  align-items: center;
  font-size: 11.5px;
  color: var(--sb-text-3);
  gap: 4px;
}
.sb-script-meta .dot { color: var(--sb-text-3); }
.sb-script-meta .sb-cat {
  background: #f1f5f9;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 500;
  color: var(--sb-text-2);
}
.sb-script-meta .sb-run-hint {
  margin-left: auto;
  opacity: 0;
  color: var(--sb-primary);
  font-weight: 500;
}
.sb-script-card:hover .sb-run-hint { opacity: 1; }

/* 「跑上次」常驻可见：它是高频路径，不应该藏在 hover 里 */
.sb-script-meta .sb-rerun-hint {
  margin-left: auto;
  padding: 0 2px;
  font-size: 11.5px;
}
.sb-script-meta .sb-rerun-hint + .sb-run-hint { margin-left: 4px; }
</style>