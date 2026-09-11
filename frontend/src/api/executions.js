import http from './http'

// payload may include: scriptId, tenantId, params, presetId, fileInputs: {paramName: serverPath}
export const execute = (payload) =>
  http.post('/executions', payload).then((r) => r.data)

export const readStdout = (id) =>
  http.get(`/executions/${id}/stdout`, { responseType: 'text' }).then((r) => r.data)

export const readStderr = (id) =>
  http.get(`/executions/${id}/stderr`, { responseType: 'text' }).then((r) => r.data)

// V2: cancel a running execution by id. Idempotent — already-finished
// executions return ok with state=SUCCESS/FAILED/TIMEOUT instead of erroring.
export const cancelExecution = (id) =>
  http.post(`/executions/${id}/cancel`).then((r) => r.data)

// V2: peek at the running state of an execution id. Used by the cancel
// button to verify the script is actually still alive before showing a toast.
export const executionState = (id) =>
  http.get(`/executions/${id}/state`).then((r) => r.data)

// V2: list currently-running executions for a (scriptId, tenantId) pair.
// Used to find the executionId that the front-end is blocked waiting on.
export const activeExecutions = (scriptId, tenantId) => {
  const params = {}
  if (scriptId != null) params.scriptId = scriptId
  if (tenantId != null) params.tenantId = tenantId
  return http.get('/executions/active', { params }).then((r) => r.data)
}