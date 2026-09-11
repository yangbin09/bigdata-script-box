import http from './http'

// V2: preview the auto-cleanup result without deleting anything. Returns
// candidate counts for history, artifacts and execution dirs.
export const previewCleanup = () =>
  http.get('/admin/cleanup/preview').then((r) => r.data)

// V2: actually apply the cleanup. Returns deleted counts.
export const applyCleanup = () =>
  http.post('/admin/cleanup/apply').then((r) => r.data)

// V2: persisted runtime settings. Keys are dotted (e.g.
// 'cleanup.historyDays'); values are strings.
export const getSettings = () =>
  http.get('/admin/settings').then((r) => r.data)

export const updateSettings = (kv) =>
  http.put('/admin/settings', kv).then((r) => r.data)