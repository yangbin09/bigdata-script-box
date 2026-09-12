import http from './http'

export const listQuickActions = () => http.get('/quick-actions')

export const createQuickAction = (qa) => http.post('/quick-actions', qa)

export const updateQuickAction = (id, qa) => http.put(`/quick-actions/${id}`, qa)

export const deleteQuickAction = (id) => http.delete(`/quick-actions/${id}`)

export const reorderQuickActions = (orderedIds) =>
  http.post('/quick-actions/reorder', orderedIds)