import http from './http'

// ===== Presets =====
export const listPresets = (scriptId) =>
  http.get(`/scripts/${scriptId}/presets`).then((r) => r.data)

export const createPreset = (scriptId, payload) =>
  http.post(`/scripts/${scriptId}/presets`, payload).then((r) => r.data)

export const updatePreset = (scriptId, id, payload) =>
  http.put(`/scripts/${scriptId}/presets/${id}`, payload).then((r) => r.data)

export const deletePreset = (scriptId, id) =>
  http.delete(`/scripts/${scriptId}/presets/${id}`).then((r) => r.data)

// ===== Versions =====
export const listVersions = (scriptId) =>
  http.get(`/scripts/${scriptId}/versions`).then((r) => r.data)

export const getVersion = (scriptId, id) =>
  http.get(`/scripts/${scriptId}/versions/${id}`).then((r) => r.data)

export const rollbackToVersion = (scriptId, id) =>
  http.post(`/scripts/${scriptId}/versions/${id}/rollback`).then((r) => r.data)

// ===== Global Variables =====
export const listGlobalVariables = () =>
  http.get('/global-variables').then((r) => r.data)

export const createGlobalVariable = (payload) =>
  http.post('/global-variables', payload).then((r) => r.data)

export const updateGlobalVariable = (id, payload) =>
  http.put(`/global-variables/${id}`, payload).then((r) => r.data)

export const deleteGlobalVariable = (id) =>
  http.delete(`/global-variables/${id}`).then((r) => r.data)

// ===== Scenarios =====
export const listScenarios = () =>
  http.get('/scenarios').then((r) => r.data)

export const getScenario = (id) =>
  http.get(`/scenarios/${id}`).then((r) => r.data)

export const createScenario = (payload) =>
  http.post('/scenarios', payload).then((r) => r.data)

export const updateScenario = (id, payload) =>
  http.put(`/scenarios/${id}`, payload).then((r) => r.data)

export const deleteScenario = (id) =>
  http.delete(`/scenarios/${id}`).then((r) => r.data)

export const replaceScenarioSteps = (id, steps) =>
  http.put(`/scenarios/${id}/steps`, steps).then((r) => r.data)

export const runScenario = (id, tenantId) =>
  http.post(`/scenarios/${id}/run`, null, { params: { tenantId } }).then((r) => r.data)

// V2: count of execution_history rows that ran as part of this scenario.
export const scenarioRelatedCounts = (id) =>
  http.get(`/scenarios/${id}/related-counts`).then((r) => r.data)

// ===== Precheck =====
export const runPrecheck = (scriptId) =>
  http.post(`/scripts/${scriptId}/precheck`).then((r) => r.data)

export const getPrecheck = (scriptId) =>
  http.get(`/scripts/${scriptId}/precheck`).then((r) => r.data)

export const savePrecheck = (scriptId, payload) =>
  http.put(`/scripts/${scriptId}/precheck`, payload).then((r) => r.data)

// ===== Dry Run =====
export const dryRun = (payload) =>
  http.post('/executions/preview', payload).then((r) => r.data)

// ===== File Uploads =====
export const uploadFile = (file, onProgress) => {
  const fd = new FormData()
  fd.append('file', file)
  return http.post('/uploads', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: (e) => onProgress && onProgress(e)
  }).then((r) => r.data)
}

// ===== Batch =====
export const runBatch = (payload) =>
  http.post('/batches/execute', payload).then((r) => r.data)

export const getBatch = (batchId) =>
  http.get(`/batches/${batchId}`).then((r) => r.data)

// ===== Package Import / Export =====
export const exportScript = (id) =>
  http.get(`/scripts/${id}/export`, { responseType: 'blob' }).then((r) => r.data)

export const importScript = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return http.post('/scripts/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }).then((r) => r.data)
}