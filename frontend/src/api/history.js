import http from './http'

export const listHistory = (limit = 200) =>
  http.get('/history', { params: { limit } }).then((r) => r.data)

export const getHistory = (id) =>
  http.get(`/history/${id}`).then((r) => r.data)