import http from './http'

export const execute = (scriptId, tenantId, params) =>
  http.post('/executions', { scriptId, tenantId, params }).then((r) => r.data)

export const readStdout = (id) =>
  http.get(`/executions/${id}/stdout`, { responseType: 'text' }).then((r) => r.data)

export const readStderr = (id) =>
  http.get(`/executions/${id}/stderr`, { responseType: 'text' }).then((r) => r.data)