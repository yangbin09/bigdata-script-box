<!--
  GlobalSearchPanel — Cmd+K / Ctrl+K launcher.

  V3 (PR-10): 居中弹窗 640×60vh，分四组：
   - 脚本（按 name / displayName / description）
   - 快捷操作（保存过的脚本 + 租户 + 参数组合）
   - 租户（按 name / authName）
   - 正在运行（executionStore.activeIds 的实时快照）

  行为：
   * 无查询词时显示「最近访问」+ 正在运行
   * 方向键 / Enter 选择；Esc 关闭
   * 选中不直接执行，统一走 router.push 打开对应页面
   * Cmd+Enter 在租户上：跳到租户测试；其他组回车普通跳转
   * 跨页跳转时面板保留查询词（uiStore.globalSearchQuery）
-->
<template>
  <el-dialog
    v-model="open"
    width="640px"
    top="10vh"
    :modal="true"
    :show-close="false"
    :align-center="false"
    :close-on-click-modal="true"
    :close-on-press-escape="true"
    class="sb-search-dialog"
    @opened="onOpen"
    @closed="onClosed"
  >
    <template #header>
      <div class="sb-search-input-wrap">
        <el-icon :size="16" class="sb-search-icon"><Search /></el-icon>
        <input
          ref="inputRef"
          v-model="query"
          class="sb-search-input"
          type="text"
          placeholder="搜索脚本 / 快捷操作 / 租户 / 正在运行…  (按 Esc 关闭)"
          @keydown.down.prevent="moveSel(1)"
          @keydown.up.prevent="moveSel(-1)"
          @keydown.enter.prevent="onEnter"
          @keydown.meta.enter.prevent="onMetaEnter"
          @keydown.ctrl.enter.prevent="onMetaEnter"
        />
        <span class="sb-search-hint">⌘K</span>
      </div>
    </template>

    <div class="sb-search-body" v-loading="loading">
      <div v-if="lastError" class="sb-search-error">{{ lastError }}</div>

      <!-- 正在运行 (always shown if non-empty) -->
      <section v-if="results.active.length" class="sb-search-group">
        <div class="sb-group-head">
          <el-icon><Loading /></el-icon>
          <span>正在运行 ({{ results.active.length }})</span>
        </div>
        <ul class="sb-search-list">
          <li
            v-for="(h, idx) in results.active"
            :key="`a-${h.id}`"
            class="sb-search-item"
            :class="{ active: selIndex === itemIndex('active', idx) }"
            @mouseenter="selIndex = itemIndex('active', idx)"
            @click="select('active', idx, h)"
          >
            <el-icon class="sb-item-icon"><VideoPlay /></el-icon>
            <div class="sb-item-main">
              <div class="sb-item-title">#{{ h.id }} · {{ h.scriptName }}</div>
              <div class="sb-item-sub">{{ h.tenantName }} · 已运行 {{ Math.round((Date.now() - (h.startedAtMs || 0)) / 1000) }}s</div>
            </div>
            <el-tag size="small" type="warning" effect="plain" disable-transitions>RUNNING</el-tag>
          </li>
        </ul>
      </section>

      <!-- 脚本 -->
      <section v-if="results.scripts.length" class="sb-search-group">
        <div class="sb-group-head">
          <el-icon><Document /></el-icon>
          <span>脚本 ({{ results.scripts.length }})</span>
        </div>
        <ul class="sb-search-list">
          <li
            v-for="(s, idx) in results.scripts"
            :key="`s-${s.id}`"
            class="sb-search-item"
            :class="{ active: selIndex === itemIndex('scripts', idx) }"
            @mouseenter="selIndex = itemIndex('scripts', idx)"
            @click="select('scripts', idx, s)"
          >
            <el-icon class="sb-item-icon"><Document /></el-icon>
            <div class="sb-item-main">
              <div class="sb-item-title">{{ s.displayName || s.name }}</div>
              <div class="sb-item-sub">{{ s.description || s.name }} · 风险：{{ s.riskLevel || '—' }}</div>
            </div>
            <el-tag v-if="s.favorite" size="small" type="warning" effect="plain" disable-transitions>★</el-tag>
          </li>
        </ul>
      </section>

      <!-- 快捷操作 -->
      <section v-if="results.quickActions.length" class="sb-search-group">
        <div class="sb-group-head">
          <el-icon><Star /></el-icon>
          <span>快捷操作 ({{ results.quickActions.length }})</span>
        </div>
        <ul class="sb-search-list">
          <li
            v-for="(qa, idx) in results.quickActions"
            :key="`q-${qa.id}`"
            class="sb-search-item"
            :class="{ active: selIndex === itemIndex('quickActions', idx) }"
            @mouseenter="selIndex = itemIndex('quickActions', idx)"
            @click="select('quickActions', idx, qa)"
          >
            <el-icon class="sb-item-icon"><Star /></el-icon>
            <div class="sb-item-main">
              <div class="sb-item-title">{{ qa.name }}</div>
              <div class="sb-item-sub">script #{{ qa.scriptId }} · tenant #{{ qa.tenantId }}</div>
            </div>
          </li>
        </ul>
      </section>

      <!-- 租户 -->
      <section v-if="results.tenants.length" class="sb-search-group">
        <div class="sb-group-head">
          <el-icon><User /></el-icon>
          <span>租户 ({{ results.tenants.length }})</span>
        </div>
        <ul class="sb-search-list">
          <li
            v-for="(t, idx) in results.tenants"
            :key="`t-${t.id}`"
            class="sb-search-item"
            :class="{ active: selIndex === itemIndex('tenants', idx) }"
            @mouseenter="selIndex = itemIndex('tenants', idx)"
            @click="select('tenants', idx, t)"
          >
            <el-icon class="sb-item-icon"><User /></el-icon>
            <div class="sb-item-main">
              <div class="sb-item-title">{{ t.name }}</div>
              <div class="sb-item-sub">{{ t.authName || '—' }} · {{ t.description || '无描述' }}</div>
            </div>
            <el-tag v-if="t.lastTestOk === false" size="small" type="danger" effect="plain" disable-transitions>认证未通过</el-tag>
            <el-tag v-else-if="t.lastTestOk" size="small" type="success" effect="plain" disable-transitions>已认证</el-tag>
          </li>
        </ul>
      </section>

      <!-- 无结果 -->
      <div v-if="!loading && !hasAnyResults && !lastError" class="sb-search-empty muted">
        没有匹配项。试试输入脚本名、租户名或关键字。
      </div>

      <!-- 最近访问（无查询词时） -->
      <section v-if="!query.trim() && recent.items.length" class="sb-search-group">
        <div class="sb-group-head">
          <el-icon><Clock /></el-icon>
          <span>最近访问</span>
          <el-button link size="small" @click="recent.clear()">清空</el-button>
        </div>
        <ul class="sb-search-list">
          <li
            v-for="(it, idx) in recent.items.slice(0, 8)"
            :key="`r-${it.kind}-${it.id}`"
            class="sb-search-item"
            :class="{ active: selIndex === 1000 + idx }"
            @mouseenter="selIndex = 1000 + idx"
            @click="selectRecent(it)"
          >
            <el-icon class="sb-item-icon">
              <component :is="iconForKind(it.kind)" />
            </el-icon>
            <div class="sb-item-main">
              <div class="sb-item-title">{{ it.title }}</div>
              <div class="sb-item-sub">{{ it.subtitle || kindLabel(it.kind) }}</div>
            </div>
          </li>
        </ul>
      </section>
    </div>

    <template #footer>
      <div class="sb-search-foot">
        <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span>
        <span><kbd>Enter</kbd> 打开</span>
        <span><kbd>Esc</kbd> 关闭</span>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Search, Document, User, Star, Clock, Loading, VideoPlay
} from '@element-plus/icons-vue'
import { useUiStore } from '../stores/uiStore'
import { useGlobalSearch } from '../composables/useGlobalSearch'
import { useRecentStore } from '../stores/recentStore'

const router = useRouter()
const ui = useUiStore()
const recent = useRecentStore()
const { query, loading, lastError, run } = useGlobalSearch()

const open = computed({
  get: () => ui.globalSearchOpen,
  set: (v) => { if (!v) ui.closeGlobalSearch() }
})
const inputRef = ref(null)
const results = ref({ scripts: [], tenants: [], quickActions: [], active: [] })
const selIndex = ref(0)

// groups are rendered in this order; we count per-group prefix
// to flatten the keyboard-selection across all groups.
const GROUP_OFFSETS = { active: 0, scripts: 0, quickActions: 0, tenants: 0 }
function recomputeOffsets() {
  let off = 0
  GROUP_OFFSETS.active = off
  off += results.value.active.length
  GROUP_OFFSETS.scripts = off
  off += results.value.scripts.length
  GROUP_OFFSETS.quickActions = off
  off += results.value.quickActions.length
  GROUP_OFFSETS.tenants = off
}
function itemIndex(group, idx) {
  return (GROUP_OFFSETS[group] || 0) + idx
}
function totalCount() {
  return results.value.active.length
    + results.value.scripts.length
    + results.value.quickActions.length
    + results.value.tenants.length
}

const hasAnyResults = computed(() => totalCount() > 0)

function moveSel(delta) {
  const total = totalCount()
  if (!total) return
  let n = selIndex.value + delta
  if (n < 0) n = total - 1
  if (n >= total) n = 0
  selIndex.value = n
}

function findAt(idx) {
  const a = results.value.active.length
  if (idx < a) return { kind: 'active', item: results.value.active[idx] }
  let off = a
  const s = results.value.scripts.length
  if (idx < off + s) return { kind: 'scripts', item: results.value.scripts[idx - off] }
  off += s
  const q = results.value.quickActions.length
  if (idx < off + q) return { kind: 'quickActions', item: results.value.quickActions[idx - off] }
  off += q
  const t = results.value.tenants.length
  if (idx < off + t) return { kind: 'tenants', item: results.value.tenants[idx - off] }
  return null
}

function select(group, idx, item) {
  if (!item) return
  if (group === 'active') {
    recent.touch({ kind: 'active', id: item.id, title: `#${item.id} · ${item.scriptName}`, subtitle: item.tenantName })
    ui.closeGlobalSearch()
    router.push({ path: '/history', query: { id: String(item.id) } })
  } else if (group === 'scripts') {
    recent.touch({ kind: 'script', id: item.id, title: item.displayName || item.name, subtitle: item.description || item.name })
    ui.closeGlobalSearch()
    router.push({ path: '/scripts' })
  } else if (group === 'quickActions') {
    recent.touch({ kind: 'quick-action', id: item.id, title: item.name, subtitle: `script #${item.scriptId} · tenant #${item.tenantId}` })
    ui.closeGlobalSearch()
    router.push({ path: '/', query: { quickAction: String(item.id) } })
  } else if (group === 'tenants') {
    recent.touch({ kind: 'tenant', id: item.id, title: item.name, subtitle: item.authName || '' })
    ui.closeGlobalSearch()
    router.push({ path: '/tenants', query: { tenant: String(item.id) } })
  }
}

function onEnter() {
  const hit = findAt(selIndex.value)
  if (hit) select(hit.kind, 0, hit.item)
}
function onMetaEnter() {
  // Cmd+Enter on tenant → open tenant page (most common debugging action);
  // otherwise behaves like normal Enter.
  const hit = findAt(selIndex.value)
  if (hit?.kind === 'tenants') {
    select('tenants', 0, hit.item)
  } else {
    onEnter()
  }
}
function selectRecent(it) {
  if (!it) return
  if (it.kind === 'script') router.push({ path: '/scripts' })
  else if (it.kind === 'tenant') router.push({ path: '/tenants', query: { tenant: String(it.id) } })
  else if (it.kind === 'active') router.push({ path: '/history', query: { id: String(it.id) } })
  else if (it.kind === 'quick-action') router.push({ path: '/', query: { quickAction: String(it.id) } })
  ui.closeGlobalSearch()
}

function iconForKind(k) {
  if (k === 'script') return Document
  if (k === 'tenant') return User
  if (k === 'active') return VideoPlay
  if (k === 'quick-action') return Star
  return Document
}
function kindLabel(k) {
  if (k === 'script') return '脚本'
  if (k === 'tenant') return '租户'
  if (k === 'active') return '正在运行'
  if (k === 'quick-action') return '快捷操作'
  return k
}

let debounce = 0
watch(query, (v) => {
  ui.globalSearchQuery = v || ''
  clearTimeout(debounce)
  debounce = setTimeout(async () => {
    results.value = await run(v)
    recomputeOffsets()
    selIndex.value = 0
  }, 120)
})

async function onOpen() {
  recent.bootstrap()
  if (ui.globalSearchQuery && !query.value) query.value = ui.globalSearchQuery
  results.value = await run(query.value)
  recomputeOffsets()
  selIndex.value = 0
  nextTick(() => inputRef.value?.focus?.())
}
function onClosed() {
  // intentionally keep ui.globalSearchQuery so opening again restores the
  // last typed text — clears only when user explicitly empties it.
}
</script>

<style scoped>
.sb-search-dialog :deep(.el-dialog__header) {
  padding: 8px 12px;
  margin: 0;
  border-bottom: 1px solid var(--sb-border);
}
.sb-search-dialog :deep(.el-dialog__body) {
  padding: 8px 0;
  max-height: 60vh;
  overflow-y: auto;
}
.sb-search-dialog :deep(.el-dialog__footer) {
  padding: 6px 12px;
  border-top: 1px solid var(--sb-border);
}

.sb-search-input-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}
.sb-search-icon { color: var(--sb-text-muted); }
.sb-search-input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 15px;
  background: transparent;
  color: var(--sb-text);
}
.sb-search-hint {
  font-size: 11px;
  padding: 2px 6px;
  border: 1px solid var(--sb-border);
  border-radius: 4px;
  color: var(--sb-text-muted);
}

.sb-search-body {
  padding: 0 4px;
}
.sb-search-group { margin-bottom: 8px; }
.sb-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.4px;
  color: var(--sb-text-muted);
}
.sb-group-head .el-button { margin-left: auto; }

.sb-search-list { list-style: none; margin: 0; padding: 0; }
.sb-search-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  cursor: pointer;
  border-radius: 6px;
}
.sb-search-item.active,
.sb-search-item:hover {
  background: var(--sb-bg-hover, #eff6ff);
}
.sb-item-icon { color: var(--sb-text-muted); flex: 0 0 auto; }
.sb-item-main { flex: 1; min-width: 0; }
.sb-item-title {
  font-size: 13.5px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sb-item-sub {
  font-size: 11.5px;
  color: var(--sb-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sb-search-empty {
  padding: 24px 12px;
  text-align: center;
  font-size: 13px;
}
.sb-search-error {
  padding: 6px 12px;
  color: var(--el-color-danger);
  font-size: 12px;
}

.sb-search-foot {
  display: flex;
  gap: 14px;
  font-size: 11px;
  color: var(--sb-text-muted);
}
.sb-search-foot kbd {
  display: inline-block;
  padding: 1px 5px;
  border: 1px solid var(--sb-border);
  border-radius: 3px;
  background: #f9fafb;
  font-family: var(--sb-mono, monospace);
  margin-right: 3px;
}
</style>
