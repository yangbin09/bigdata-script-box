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
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import AppLayout from './components/AppLayout.vue'
import TaskCenterDrawer from './components/TaskCenterDrawer.vue'
import { useExecutionStore } from './stores/executionStore'

const exec = useExecutionStore()

onMounted(() => {
  // 启动时拉一次活跃列表 + 启动 2s 轮询
  exec.bootstrap()
})

onUnmounted(() => {
  // 关闭定时器（hot reload / 路由切换都不会进这里，但显式清理更稳）
  if (exec.pollTimer) {
    clearInterval(exec.pollTimer)
    exec.pollTimer = null
  }
})
</script>
