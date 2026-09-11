import http from './http'

export const listScripts = () =>
  http.get('/scripts').then((r) => r.data)

export const getScript = (id) =>
  http.get(`/scripts/${id}`).then((r) => r.data)

export const deleteScript = (id) =>
  http.delete(`/scripts/${id}`).then((r) => r.data)

export const setScriptEnabled = (id, enabled) =>
  http.post(`/scripts/${id}/enabled`, null, { params: { enabled } }).then((r) => r.data)

export const setScriptFavorite = (id, favorite) =>
  http.post(`/scripts/${id}/favorite`, null, { params: { favorite } }).then((r) => r.data)

export const copyScript = (id, suffix) =>
  http.post(`/scripts/${id}/copy`, null, { params: suffix ? { suffix } : {} }).then((r) => r.data)

export const listParams = (id) =>
  http.get(`/scripts/${id}/params`).then((r) => r.data)

export const replaceParams = (id, params) =>
  http.put(`/scripts/${id}/params`, params).then((r) => r.data)

export const readBody = (id) =>
  http.get(`/scripts/${id}/body`, { responseType: 'text' }).then((r) => r.data)

export const saveBody = (id, body) =>
  http.put(`/scripts/${id}/body`, { body }).then((r) => r.data)

// Multipart create/update. We pass the FormData directly so axios sets the boundary.
export const createScript = (form) =>
  http.post('/scripts', form, { headers: { 'Content-Type': 'multipart/form-data' } })
    .then((r) => r.data)

export const updateScript = (id, form) =>
  http.put(`/scripts/${id}`, form, { headers: { 'Content-Type': 'multipart/form-data' } })
    .then((r) => r.data)