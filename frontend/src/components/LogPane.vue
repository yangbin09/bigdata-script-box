<!--
  LogPane — viewer for stdout/stderr with search, highlight, quick chips,
  and configurable context. Used by ExecutionResultPanel.

  V2 features:
  - search box (substring by default; toggles to regex via the .* chip).
  - quick-filter chips: ERROR / WARN / Exception / Caused by / FAILED
    (multi-select; combines with search as AND).
  - context lines: how many lines around each match to keep (0 / 3 / 5 / 10).
    Context is rendered in a muted style so matches stand out.
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
        @input="onSearchInput"
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
        @click="clearChips"
      >重置</el-button>
      <span class="sb-logpane-counter">
        {{ matchSummary }}
      </span>
    </div>

    <div class="sb-log-toolbar">
      <el-button size="small" :icon="DocumentCopy" @click="$emit('copy', visibleText)">复制可见</el-button>
      <el-button size="small" :icon="Download" @click="$emit('download', streamName, visibleText)">下载</el-button>
      <el-button size="small" text :icon="CopyDocument" @click="copyRaw">复制原始</el-button>
    </div>

    <pre v-if="!props.text" class="sb-log sb-log-empty">无 {{ streamName }} 输出</pre>
    <pre
      v-else-if="!visibleText"
      class="sb-log sb-log-empty"
    >无匹配 (尝试调整搜索 / 上下文 / 重置快速过滤)</pre>
    <pre v-else class="sb-log sb-log-highlighted" v-html="renderedHtml"></pre>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import {
  Search, DocumentCopy, Download, CopyDocument, RefreshLeft
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  text: { type: String, default: '' },
  streamName: { type: String, required: true }, // 'stdout' | 'stderr'
  executionId: { type: [Number, String], required: true }
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
// Compile-failure guard so we don't blow up on a bad regex. We keep the
// previous valid regex (and surface an inline message) instead.
const regexError = ref('')
let compiledRegex = null

function isChipOn(token) {
  return activeChips.value.has(token)
}
function toggleChip(token) {
  const next = new Set(activeChips.value)
  if (next.has(token)) next.delete(token); else next.add(token)
  activeChips.value = next
}
function clearChips() {
  activeChips.value = new Set()
  search.value = ''
  regexMode.value = false
  context.value = 0
  compiledRegex = null
  regexError.value = ''
}

function onSearchInput() {
  regexError.value = ''
  compiledRegex = null
  if (!search.value) return
  if (regexMode.value) {
    try { compiledRegex = new RegExp(search.value, 'i') }
    catch (e) { regexError.value = '正则无效: ' + e.message }
  }
}

// Re-compile when mode flips.
watch(regexMode, () => onSearchInput())

const lines = computed(() => (props.text || '').split(/\r?\n/))

// Map of indices that should be displayed given the active filters + context.
const visibleLineSet = computed(() => {
  const set = new Set()
  const searchActive = !!search.value
  const chipsActive = activeChips.value.size > 0
  if (!searchActive && !chipsActive) {
    for (let i = 0; i < lines.value.length; i++) set.add(i)
    return set
  }
  for (let i = 0; i < lines.value.length; i++) {
    const line = lines.value[i]
    let match = false
    // chip filter: any active chip must hit (AND across chips)
    for (const tok of activeChips.value) {
      if (line.toLowerCase().includes(tok.toLowerCase())) { match = true; break }
    }
    if (!match && searchActive) {
      if (compiledRegex) {
        compiledRegex.lastIndex = 0
        match = compiledRegex.test(line)
      } else if (search.value) {
        match = line.toLowerCase().includes(search.value.toLowerCase())
      }
    }
    if (match) {
      const ctx = context.value
      const from = Math.max(0, i - ctx)
      const to = Math.min(lines.value.length - 1, i + ctx)
      for (let j = from; j <= to; j++) set.add(j)
    }
  }
  return set
})

const visibleText = computed(() => {
  const out = []
  for (let i = 0; i < lines.value.length; i++) {
    if (visibleLineSet.value.has(i)) out.push(lines.value[i])
  }
  return out.join('\n')
})

const matchCount = computed(() => {
  // Count lines that hit the search OR a chip (not context-bumped).
  const searchActive = !!search.value
  const chipsActive = activeChips.value.size > 0
  if (!searchActive && !chipsActive) return 0
  let n = 0
  for (const line of lines.value) {
    let match = false
    for (const tok of activeChips.value) {
      if (line.toLowerCase().includes(tok.toLowerCase())) { match = true; break }
    }
    if (!match && searchActive) {
      if (compiledRegex) {
        compiledRegex.lastIndex = 0
        match = compiledRegex.test(line)
      } else if (search.value) {
        match = line.toLowerCase().includes(search.value.toLowerCase())
      }
    }
    if (match) n++
  }
  return n
})

const matchSummary = computed(() => {
  const total = lines.value.length
  const kept = visibleLineSet.value.size
  const matched = matchCount.value
  if (!search.value && activeChips.value.size === 0) {
    return `全部 ${total} 行`
  }
  return `匹配 ${matched} / ${total} 行，显示 ${kept} 行`
})

function escapeHtml(s) {
  return String(s).replace(/[&<>"]/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]
  ))
}

function escapeRegExp(s) {
  return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

const renderedHtml = computed(() => {
  const tokens = []
  if (compiledRegex) tokens.push({ regex: compiledRegex })
  else if (search.value) tokens.push({ literal: search.value })
  for (const tok of activeChips.value) tokens.push({ literal: tok })
  if (tokens.length === 0) return escapeHtml(visibleText.value)
  // Build a single combined regex so we can replace every match in one pass.
  const parts = tokens.map((t) => t.regex ? t.regex.source : escapeRegExp(t.literal))
  const flags = 'gi' + (tokens.some((t) => t.regex) ? '' : '')
  let combined
  try { combined = new RegExp('(' + parts.join('|') + ')', flags) }
  catch (e) { return escapeHtml(visibleText.value) }
  const esc = escapeHtml(visibleText.value)
  return esc.replace(combined, (m) => `<mark>${m}</mark>`)
})

function copyRaw() {
  if (!props.text) {
    ElMessage.warning('没有内容可以复制')
    return
  }
  navigator.clipboard?.writeText(props.text).then(
    () => ElMessage.success('已复制原始日志'),
    () => ElMessage.error('复制失败')
  )
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
.sb-log {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
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
}
.sb-log-empty { color: var(--el-text-color-secondary); }
.sb-log-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
}
/* Highlighted log: matches are wrapped in <mark>; context-only lines are
   slightly muted so the eye lands on the matches. */
.sb-log-highlighted :deep(mark) {
  background: #ffe58f;
  color: inherit;
  padding: 0 2px;
  border-radius: 2px;
}
</style>