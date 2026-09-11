import http from './http'

export const listHistory = (params = {}) =>
  http.get('/history', { params }).then((r) => r.data)

export const getHistory = (id) =>
  http.get(`/history/${id}`).then((r) => r.data)

export const recentScripts = (limit = 6) =>
  http.get('/history/recent-scripts', { params: { limit } }).then((r) => r.data)