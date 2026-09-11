<!--
  AppLayout — sticky topbar with brand, navigation and an environment tag.
  Width: 1600px max (raised from 1400px so 4-up cards aren't squished on a 27" display).
  Environment tag pulled from /api/system/info; defaults to "Mock 环境" / "Real 环境".
-->
<template>
  <div class="sb-shell">
    <header class="sb-topbar">
      <div class="sb-brand">
        <el-icon :size="18" color="#2563eb"><Tools /></el-icon>
        <span class="sb-brand-text">BigData Script Box</span>
        <span
          class="sb-env-tag"
          :class="{ mock: envInfo.mock, real: !envInfo.mock }"
        >{{ envInfo.mock ? 'MOCK' : 'REAL' }}</span>
      </div>
      <nav class="sb-nav">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="sb-nav-item"
          :class="{ active: isActive(item.path) }"
        >
          <el-icon :size="15"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>
      <div class="sb-topbar-right">
        <span class="sb-env-name">{{ envInfo.environmentName }}</span>
        <span class="sb-status-dot" :class="{ live: ready }" />
        <span class="sb-status-text">{{ ready ? '就绪' : '加载中' }}</span>
      </div>
    </header>
    <main class="sb-main">
      <slot />
    </main>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { systemInfo } from '../api/system'

const route = useRoute()
const ready = ref(false)
const envInfo = reactive({ mock: true, environmentName: 'Mock 环境' })

const navItems = [
  { path: '/',         label: '执行中心',   icon: 'Promotion' },
  { path: '/scripts',  label: '脚本管理',   icon: 'Document' },
  { path: '/tenants',  label: '租户管理',   icon: 'User' },
  { path: '/scenarios', label: '场景',      icon: 'Connection' },
  { path: '/history',  label: '执行历史',   icon: 'Clock' },
  { path: '/settings', label: '设置',      icon: 'Setting' }
]

function isActive(p) {
  if (p === '/') return route.path === '/' || route.path === ''
  return route.path === p || route.path.startsWith(p + '/')
}

// Probe the system endpoint once on mount — confirms the JAR is up and gives us
// the mock/real flag + environment name displayed in the topbar tag.
onMounted(async () => {
  try {
    const info = await systemInfo()
    if (info) Object.assign(envInfo, info)
    ready.value = true
  } catch (_) {
    ready.value = false
  }
})
</script>

<style scoped>
.sb-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.sb-topbar {
  height: 48px;
  display: flex;
  align-items: center;
  background: #ffffff;
  border-bottom: 1px solid var(--sb-border);
  padding: 0 20px;
  gap: 32px;
  position: sticky;
  top: 0;
  z-index: 10;
}

.sb-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 14px;
}

.sb-brand-text {
  color: var(--sb-text);
}

.sb-env-tag {
  font-size: 10.5px;
  letter-spacing: 0.5px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 700;
  line-height: 16px;
  border: 1px solid transparent;
}

.sb-env-tag.mock {
  background: #fff7ed;   /* orange-50 */
  color: #c2410c;       /* orange-700 */
  border-color: #fed7aa;
}

.sb-env-tag.real {
  background: #f0fdf4;   /* green-50 */
  color: #15803d;       /* green-700 */
  border-color: #bbf7d0;
}

.sb-nav {
  display: flex;
  gap: 4px;
  flex: 1;
}

.sb-nav-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 4px;
  text-decoration: none;
  color: var(--sb-text-2);
  font-size: 13px;
  transition: none;
}

.sb-nav-item:hover {
  background: #f3f4f6;
  color: var(--sb-text);
}

.sb-nav-item.active {
  background: #eff6ff;
  color: var(--sb-primary);
  font-weight: 500;
}

.sb-topbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--sb-text-3);
}

.sb-env-name {
  font-weight: 500;
  color: var(--sb-text-2);
}

.sb-status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #d4d6da;
}

.sb-status-dot.live {
  background: var(--sb-success);
}

.sb-main {
  flex: 1;
  padding: 20px 24px;
  max-width: 1600px;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}
</style>