<template>
  <AppLayout>
    <router-view />
  </AppLayout>
  <!--
    V3 (PR-0): TaskCenterDrawer 是任务中心承载组件，
    顶栏的"任务中心"按钮和 ⌘K 入口都打开它。挂在 App 根而不是某个 view
    —— 切页时抽屉状态不丢。
  -->
  <TaskCenterDrawer />
  <!--
    V3 (PR-10): 全局查找面板 (⌘K / Ctrl+K)。同样挂在根，跨页面状态由
    uiStore.globalSearchQuery 保留。
  -->
  <GlobalSearchPanel />
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import AppLayout from './components/AppLayout.vue'
import TaskCenterDrawer from './components/TaskCenterDrawer.vue'
import GlobalSearchPanel from './components/GlobalSearchPanel.vue'
import { useExecutionStore } from './stores/executionStore'
import { useRecentStore } from './stores/recentStore'

const exec = useExecutionStore()
const recent = useRecentStore()

onMounted(() => {
  // 启动时拉一次活跃列表 + 启动 2s 轮询
  exec.bootstrap()
  recent.bootstrap()
})

onUnmounted(() => {
  // 关闭定时器（hot reload / 路由切换都不会进这里，但显式清理更稳）
  if (exec.pollTimer) {
    clearInterval(exec.pollTimer)
    exec.pollTimer = null
  }
})
</script>
