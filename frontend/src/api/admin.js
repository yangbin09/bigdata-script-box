import http from './http'

// V2: manual cleanup. previewCleanup POSTs the four retention numbers
// and gets back a server-side snapshot with a previewId and TTL. The
// operator confirms by sending that previewId + the literal token
// 'CLEAN' to executeCleanup.
export const previewCleanup = (retention) =>
  http.post('/admin/cleanup/preview', retention)

export const executeCleanup = (body) =>
  http.post('/admin/cleanup/execute', body)

export const listCleanupHistory = (limit = 20) =>
  http.get('/admin/cleanup/history', { params: { limit } })

// V2: persisted runtime settings. Keys are dotted (e.g.
// 'cleanup.historyDays'); values are strings.
export const getSettings = () =>
  http.get('/admin/settings')

export const updateSettings = (kv) =>
  http.put('/admin/settings', kv)
