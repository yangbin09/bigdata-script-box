// composables/useGlobalSearch.js — multi-source fuzzy search for the Cmd+K panel.
//
// Source groups:
//   - scripts : name / displayName / description (case-insensitive substring)
//   - tenants : name / authName / description
//   - active  : currently running executions from executionStore.activeIds
//
// Caching strategy:
//   First call lazily loads each source via its API; subsequent calls reuse
//   the in-memory arrays until the user explicitly refreshes (Cmd+R inside
//   the panel) or the cache TTL expires (60s).

import { ref } from 'vue'
import { listScripts } from '../api/scripts'
import { listTenants } from '../api/tenants'
import { useExecutionStore } from '../stores/executionStore'

const TTL_MS = 60_000
const cache = {
  scripts: { data: null, at: 0 },
  tenants: { data: null, at: 0 }
}

function fresh(slot) {
  return slot.data && (Date.now() - slot.at) < TTL_MS
}

async function ensureScripts() {
  if (fresh(cache.scripts)) return cache.scripts.data
  const data = (await listScripts().catch(() => [])) || []
  cache.scripts = { data, at: Date.now() }
  return data
}
async function ensureTenants() {
  if (fresh(cache.tenants)) return cache.tenants.data
  const data = (await listTenants().catch(() => [])) || []
  cache.tenants = { data, at: Date.now() }
  return data
}

function norm(s) {
  return (s || '').toString().trim().toLowerCase()
}

function score(needle, hay) {
  if (!needle) return 1
  const h = norm(hay)
  if (!h) return 0
  if (h === needle) return 100
  if (h.startsWith(needle)) return 80
  if (h.includes(needle)) return 50
  return 0
}

function search(array, needle, fields) {
  if (!needle) return array.slice(0, 50)
  const n = norm(needle)
  const scored = []
  for (const item of array) {
    let best = 0
    for (const f of fields) {
      const s = score(n, item[f])
      if (s > best) best = s
    }
    if (best > 0) scored.push({ item, s: best })
  }
  scored.sort((a, b) => b.s - a.s)
  return scored.slice(0, 20).map((x) => x.item)
}

// Module-level refs so multiple consumers (panel + keyboard listener)
// share the same query.
const query = ref('')
const loading = ref(false)
const lastError = ref(null)

export function useGlobalSearch() {
  const exec = useExecutionStore()

  async function run(q) {
    query.value = q ?? query.value
    loading.value = true
    lastError.value = null
    try {
      const [scripts, tenants] = await Promise.all([
        ensureScripts(),
        ensureTenants()
      ])
      const n = norm(query.value)
      return {
        scripts: search(scripts, n, ['name', 'displayName', 'description']),
        tenants: search(tenants, n, ['name', 'authName', 'description']),
        active: (() => {
          const ids = exec.activeIds || []
          const list = ids.map((id) => exec.byId.get(Number(id))).filter(Boolean)
          if (!n) return list
          return list.filter((h) =>
            norm(h.scriptName).includes(n) ||
            norm(h.tenantName).includes(n) ||
            String(h.id).includes(n))
        })()
      }
    } catch (e) {
      lastError.value = e?.message || '搜索失败'
      return { scripts: [], tenants: [], active: [] }
    } finally {
      loading.value = false
    }
  }

  function invalidate() {
    cache.scripts = { data: null, at: 0 }
    cache.tenants = { data: null, at: 0 }
  }

  return { query, loading, lastError, run, invalidate }
}
