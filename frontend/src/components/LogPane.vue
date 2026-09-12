<!--
  LogPane — viewer for stdout/stderr with search, highlight, quick chips,
  and configurable context. Used by ExecutionResultPanel.

  V2 features:
  - search box (substring by default; toggles to regex via the 正则 chip).
  - quick-filter chips: ERROR / WARN / Exception / Caused by / FAILED
    (multi-select; combines with search as OR within a line).
  - context lines: how many lines around each match to keep (0 / 3 / 5 / 10).
  - highlight: every match in the kept lines is wrapped in <mark>; the
    preview pane is a v-html so we can colour the matches.
  - download: streams whatever the user is currently viewing, not the
    whole log.
  - line counter: "showing X / Y lines" so users know when the filter
    truncated a big log.

  Empty / no-match states are surfaced as '无 stdout 输出' / '无匹配'.
-->
<template>
  <div class="sb-logpane">
    <div class="sb-logpane-toolbar">
      <el-input
        v-model="search"
        placeholder="搜索 (字符串或正则)"
        clearable
        size="small"
        :prefix-icon="Search"
        class="sb-logpane-search"
      />
      <el-checkbox v-model="regexMode" size="small" class="sb-logpane-regex">正则</el-checkbox>
      <el-button-group size="small" class="sb-logpane-context">
        <el-button
          v-for="c in CONTEXTS"
          :key="c"
          :type="context === c ? 'primary' : ''"
          plain
          @click="context = c"
        >上下文 {{ c }}</el-button>
      </el-button-group>
    </div>
    <div v-if="regexError" class="sb-logpane-regex-error">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ regexError }}</span>
    </div>

    <div class="sb-logpane-chips">
      <span class="sb-logpane-chips-label">快速过滤:</span>
      <el-check-tag
        v-for="c in QUICK_CHIPS"
        :key="c.token"
        :checked="isChipOn(c.token)"
        @change="toggleChip(c.token)"
      >{{ c.label }}</el-check-tag>
      <el-button
        size="small"
        text
        :icon="RefreshLeft"
        @click="clearFilters"
      >重置</el-button>
      <span class="sb-logpane-counter">
        {{ matchSummary }}
      </span>
    </div>

    <div class="sb-logpane-actions">
      <el-button size="small" :icon="DocumentCopy" @click="$emit('copy', visibleText)">复制可见</el-button>
      <el-button size="small" :icon="Download" @click="$emit('download', streamName, visibleText)">下载</el-button>
      <el-button size="small" text :icon="CopyDocument" @click="copyRaw">复制原始</el-button>
    </div>

    <pre v-if="!text" class="sb-logpane-pre sb-logpane-empty">无 {{ streamName }} 输出</pre>
    <pre
      v-else-if="!visibleText"
      class="sb-logpane-pre sb-logpane-empty"
    >无匹配 (尝试调整搜索 / 上下文 / 重置快速过滤)</pre>
    <pre v-else class="sb-logpane-pre sb-logpane-highlighted" v-html="renderedHtml"></pre>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import {
  Search, DocumentCopy, Download, CopyDocument, RefreshLeft, WarningFilled
} from '@element-plus/icons-vue'
import { copyText } from '../utils/clipboard'

const props = defineProps({
  text: { type: String, default: '' },
  streamName: { type: String, required: true }, // 'stdout' | 'stderr'
  // Identifies the run being viewed: when the parent swaps to another
  // execution the filters reset, so a stale search can't hide the new log.
  executionId: { type: [Number, String], default: null }
})

defineEmits(['copy', 'download'])

// Quick-filter chips. The token is matched case-insensitively against each
// log line; the label is shown in the UI.
const QUICK_CHIPS = [
  { token: 'ERROR',     label: 'ERROR' },
  { token: 'WARN',      label: 'WARN' },
  { token: 'Exception', label: 'Exception' },
  { token: 'Caused by', label: 'Caused by' },
  { token: 'FAILED',    label: 'FAILED' }
]

const CONTEXTS = [0, 3, 5, 10]

const search = ref('')
const regexMode = ref(false)
const context = ref(0)
const activeChips = ref(new Set())

function isChipOn(token) {
  return activeChips.value.has(token)
}
function toggleChip(token) {
  const next = new Set(activeChips.value)
  if (next.has(token)) next.delete(token); else next.add(token)
  activeChips.value = next
}
function clearFilters() {
  activeChips.value = new Set()
  search.value = ''
  regexMode.value = false
  context.value = 0
}

// Rebuild the filter state when the parent switches execution.
watch(() => props.executionId, () => clearFilters())

/**
 * Compiled matcher. A computed (not a mutated closure cell) so that toggling
 * between substring and regex mode actually invalidates every derived value —
 * previously the regex was cached in a plain variable and the results stayed in
 * the old mode until the next keystroke.
 */
const matcher = computed(() => {
  const q = search.value
  if (!q) return {}
  if (regexMode.value) {
    try { return { re: new RegExp(q, 'i') } }
    catch (e) { return { error: '正则无效: ' + e.message } }
  }
  return { literal: q.toLowerCase() }
})

const regexError = computed(() => matcher.value.error || '')

const lines = computed(() => (props.text || '').split(/\r?\n/))

/**
 * Single pass over the log: which lines match, and how many. The old code ran
 * the same matching loop twice (visible lines + match count) and then walked
 * the line array again for the text and the HTML.
 */
const matchInfo = computed(() => {
  const m = matcher.value
  const chips = [...activeChips.value].map((t) => t.toLowerCase())
  const literal = m.literal || null
  const re = m.re || null
  const active = chips.length > 0 || !!literal || !!re
  const idx = new Set()
  if (!active) return { idx, active: false, count: 0 }
  const all = lines.value
  let count = 0
  for (let i = 0; i < all.length; i++) {
    const line = all[i]
    const low = line.toLowerCase()
    let hit = chips.length ? chips.some((t) => low.includes(t)) : false
    if (!hit && literal) hit = low.includes(literal)
    if (!hit && re) hit = re.test(line)
    if (hit) { idx.add(i); count++ }
  }
  return { idx, active: true, count }
})

// Indices kept on screen: every match plus its context window.
const visibleLineSet = computed(() => {
  const { idx, active } = matchInfo.value
  const all = lines.value
  const out = new Set()
  if (!active) {
    for (let i = 0; i < all.length; i++) out.add(i)
    return out
  }
  const ctx = context.value
  for (const i of idx) {
    const from = Math.max(0, i - ctx)
    const to = Math.min(all.length - 1, i + ctx)
    for (let j = from; j <= to; j++) out.add(j)
  }
  return out
})

const visibleText = computed(() => {
  const all = lines.value
  const kept = visibleLineSet.value
  const out = []
  for (let i = 0; i < all.length; i++) {
    if (kept.has(i)) out.push(all[i])
  }
  return out.join('\n')
})

const matchSummary = computed(() => {
  const total = lines.value.length
  if (!matchInfo.value.active) return `全部 ${total} 行`
  return `匹配 ${matchInfo.value.count} / ${total} 行，显示 ${visibleLineSet.value.size} 行`
})

function escapeHtml(s) {
  return String(s).replace(/[&<>"]/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]
  ))
}

function escapeRegExp(s) {
  return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * Highlight matches by splitting the RAW text on the combined pattern and
 * escaping each fragment afterwards. Matching against pre-escaped HTML (the
 * previous approach) never highlighted `&`, `<`, `>` or `"` and could inject a
 * <mark> into the middle of an entity.
 */
const renderedHtml = computed(() => {
  const text = visibleText.value
  const tokens = []
  if (matcher.value.re) tokens.push(matcher.value.re.source)
  else if (matcher.value.literal) tokens.push(escapeRegExp(matcher.value.literal))
  for (const tok of activeChips.value) tokens.push(escapeRegExp(tok))
  if (!tokens.length) return escapeHtml(text)
  let re
  try { re = new RegExp('(' + tokens.join('|') + ')', 'gi') } catch { return escapeHtml(text) }
  // A capturing group makes every odd chunk of split() a match.
  return text
    .split(re)
    .map((chunk, i) => (i % 2 ? `<mark>${escapeHtml(chunk)}</mark>` : escapeHtml(chunk)))
    .join('')
})

function copyRaw() {
  copyText(props.text, '没有内容可以复制', '已复制原始日志')
}
</script>

<style scoped>
.sb-logpane-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.sb-logpane-search { flex: 1; min-width: 0; }
.sb-logpane-regex { white-space: nowrap; }
.sb-logpane-context { white-space: nowrap; }
.sb-logpane-regex-error {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  padding: 4px 8px;
  background: #fff7ed;
  border: 1px solid #fed7aa;
  border-radius: 4px;
  font-size: 12px;
  color: #c2410c;
}
.sb-logpane-chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}
.sb-logpane-chips-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-right: 2px;
}
.sb-logpane-counter {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.sb-logpane-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
}
/* Own class rather than shadowing the global `.sb-log`: this pane is a light
   "paper" log, the result/params panes are dark terminals. */
.sb-logpane-pre {
  font-family: var(--sb-mono);
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.5;
  max-height: 360px;
  overflow: auto;
  background: var(--sb-bg-soft);
  border: 1px solid var(--sb-border);
  border-radius: 6px;
  padding: 8px 10px;
  margin: 0;
}
.sb-logpane-empty { color: var(--el-text-color-secondary); font-style: italic; }
/* Highlighted log: matches are wrapped in <mark>. */
.sb-logpane-highlighted :deep(mark) {
  background: #ffe58f;
  color: inherit;
  padding: 0 2px;
  border-radius: 2px;
}
</style>
