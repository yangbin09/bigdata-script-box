import http from './http'

export const listHistory = (params = {}) =>
  http.get('/history', { params })

export const getHistory = (id) =>
  http.get(`/history/${id}`)

export const recentScripts = (limit = 6) =>
  http.get('/history/recent-scripts', { params: { limit } })
