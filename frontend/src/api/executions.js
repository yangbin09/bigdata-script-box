import http from './http'

// payload may include: scriptId, tenantId, params, presetId, fileInputs: {paramName: serverPath}
export const execute = (payload) =>
  http.post('/executions', payload).then((r) => r.data)

export const readStdout = (id) =>
  http.get(`/executions/${id}/stdout`, { responseType: 'text' }).then((r) => r.data)

export const readStderr = (id) =>
  http.get(`/executions/${id}/stderr`, { responseType: 'text' }).then((r) => r.data)