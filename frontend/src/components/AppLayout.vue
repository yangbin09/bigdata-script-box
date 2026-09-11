<template>
  <div class="sb-shell">
    <header class="sb-topbar">
      <div class="sb-brand">
        <el-icon :size="18" color="#2563eb"><Tools /></el-icon>
        <span class="sb-brand-text">BigData Script Box</span>
        <span class="sb-brand-tag">dev tool</span>
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
        <span class="sb-status-dot" :class="{ live: ready }" />
        <span class="sb-status-text">{{ ready ? 'ready' : 'loading…' }}</span>
      </div>
    </header>
    <main class="sb-main">
      <slot />
    </main>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { listTenants } from '../api/tenants'

const route = useRoute()
const ready = ref(false)

const navItems = [
  { path: '/',         label: '执行中心',   icon: 'Promotion' },
  { path: '/scripts',  label: '脚本管理',   icon: 'Document' },
  { path: '/tenants',  label: '租户管理',   icon: 'User' },
  { path: '/history',  label: '执行历史',   icon: 'Clock' }
]

function isActive(p) {
  if (p === '/') return route.path === '/' || route.path === ''
  return route.path === p || route.path.startsWith(p + '/')
}

// Probe API once on mount — confirms the JAR is up and the proxy works.
onMounted(async () => {
  try {
    await listTenants()
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

.sb-brand-tag {
  font-size: 11px;
  background: #eef2ff;
  color: #4f46e5;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 500;
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
  gap: 6px;
  font-size: 12px;
  color: var(--sb-text-3);
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
  max-width: 1400px;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}
</style>