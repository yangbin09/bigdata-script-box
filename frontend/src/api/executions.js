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

// V2: re-run an execution exactly as it ran, using the snapshotted body +
// params stored on the history row. The server temporarily writes the
// snapshotted body into the script's file, runs, then restores.
export const rerunExecution = (id) =>
  http.post(`/executions/${id}/rerun`).then((r) => r.data)

// V2: list the artifacts registered for an execution (files the script
// wrote under $ARTIFACT_DIR). Empty array when nothing was produced.
export const listArtifacts = (id) =>
  http.get(`/executions/${id}/artifacts`).then((r) => r.data)

// V2: artifact download URL — the browser navigates to it directly so
// the Content-Disposition header drives the filename. Caller should use
// `window.location.href = artifactDownloadUrl(id, name)` or anchor href.
export const artifactDownloadUrl = (id, name) =>
  `/api/executions/${id}/artifacts/${encodeURIComponent(name)}`

// V2: read the structured result.json for an execution. The endpoint
// returns ApiResponse.ok({...}) or 404-equivalent when the script didn't
// write a result.json.
export const readResult = (id) =>
  http.get(`/executions/${id}/result`).then((r) => r.data)