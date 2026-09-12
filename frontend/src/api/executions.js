import http from './http'

// The three long-running endpoints below block on the server until the script
// (or every row of the batch) finishes, and the server enforces the script's
// own timeout. They therefore default to "no client-side timeout" instead of
// the 60 s instance default, which used to abort 1-minute-plus runs while the
// backend kept going. Callers may pass an explicit budget.
const LONG_RUNNING = { timeout: 0 }

// payload may include: scriptId, tenantId, params, presetId, fileInputs: {paramName: serverPath}
export const execute = (payload, timeoutMs) =>
  http.post('/executions', payload, timeoutMs == null ? LONG_RUNNING : { timeout: timeoutMs })

export const readStdout = (id) =>
  http.get(`/executions/${id}/stdout`, { responseType: 'text' })

export const readStderr = (id) =>
  http.get(`/executions/${id}/stderr`, { responseType: 'text' })

// V2: cancel a running execution by id. Idempotent — already-finished
// executions return ok with state=SUCCESS/FAILED/TIMEOUT instead of erroring.
export const cancelExecution = (id) =>
  http.post(`/executions/${id}/cancel`)

// V2: peek at the running state of an execution id. Used by the cancel
// button to verify the script is actually still alive before showing a toast.
export const executionState = (id) =>
  http.get(`/executions/${id}/state`)

// V2: list currently-running executions for a (scriptId, tenantId) pair.
// Used to find the executionId that the front-end is blocked waiting on.
export const activeExecutions = (scriptId, tenantId) => {
  const params = {}
  if (scriptId != null) params.scriptId = scriptId
  if (tenantId != null) params.tenantId = tenantId
  return http.get('/executions/active', { params })
}

// V2: re-run an execution exactly as it ran, using the snapshotted body +
// params stored on the history row. The server temporarily writes the
// snapshotted body into the script's file, runs, then restores.
export const rerunExecution = (id, timeoutMs) =>
  http.post(`/executions/${id}/rerun`, null, timeoutMs == null ? LONG_RUNNING : { timeout: timeoutMs })

// V2: list the artifacts registered for an execution (files the script
// wrote under $ARTIFACT_DIR). Empty array when nothing was produced.
export const listArtifacts = (id) =>
  http.get(`/executions/${id}/artifacts`)

// V2: artifact download URL — the browser navigates to it directly so
// the Content-Disposition header drives the filename. Caller should use
// `window.location.href = artifactDownloadUrl(id, name)` or anchor href.
export const artifactDownloadUrl = (id, name) =>
  `/api/executions/${id}/artifacts/${encodeURIComponent(name)}`

// V2: read the structured result.json for an execution. Resolves to the parsed
// object, or null when the script wrote no result.json (a normal state, not an
// error).
export const readResult = (id) =>
  http.get(`/executions/${id}/result`)
