import http from './http'

export const listScripts = () =>
  http.get('/scripts')

export const getScript = (id) =>
  http.get(`/scripts/${id}`)

export const deleteScript = (id) =>
  http.delete(`/scripts/${id}`)

export const setScriptEnabled = (id, enabled) =>
  http.post(`/scripts/${id}/enabled`, null, { params: { enabled } })

export const setScriptFavorite = (id, favorite) =>
  http.post(`/scripts/${id}/favorite`, null, { params: { favorite } })

export const copyScript = (id, suffix) =>
  http.post(`/scripts/${id}/copy`, null, { params: suffix ? { suffix } : {} })

export const listParams = (id) =>
  http.get(`/scripts/${id}/params`)

export const replaceParams = (id, params) =>
  http.put(`/scripts/${id}/params`, params)

export const readBody = (id) =>
  http.get(`/scripts/${id}/body`, { responseType: 'text' })

export const saveBody = (id, body) =>
  http.put(`/scripts/${id}/body`, { body })

// V2: probe a body for bash syntax errors without persisting. The same
// gate fires on the actual save; this is just an editor convenience.
export const syntaxCheck = (body) =>
  http.post('/scripts/syntax-check', { body })

// Multipart create/update. We pass the FormData directly so axios sets the boundary.
export const createScript = (form) =>
  http.post('/scripts', form, { headers: { 'Content-Type': 'multipart/form-data' } })

export const updateScript = (id, form) =>
  http.put(`/scripts/${id}`, form, { headers: { 'Content-Type': 'multipart/form-data' } })

// V2: counts of records that reference this script. Shown in the delete
// confirmation dialog so the operator sees what will be orphaned.
export const scriptRelatedCounts = (id) =>
  http.get(`/scripts/${id}/related-counts`)
