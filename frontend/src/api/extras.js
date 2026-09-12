import http from './http'

// ===== Presets =====
export const listPresets = (scriptId) =>
  http.get(`/scripts/${scriptId}/presets`)

export const createPreset = (scriptId, payload) =>
  http.post(`/scripts/${scriptId}/presets`, payload)

export const updatePreset = (scriptId, id, payload) =>
  http.put(`/scripts/${scriptId}/presets/${id}`, payload)

export const deletePreset = (scriptId, id) =>
  http.delete(`/scripts/${scriptId}/presets/${id}`)

// ===== Versions =====
export const listVersions = (scriptId) =>
  http.get(`/scripts/${scriptId}/versions`)

export const getVersion = (scriptId, id) =>
  http.get(`/scripts/${scriptId}/versions/${id}`)

export const rollbackToVersion = (scriptId, id) =>
  http.post(`/scripts/${scriptId}/versions/${id}/rollback`)

// ===== Global Variables =====
export const listGlobalVariables = () =>
  http.get('/global-variables')

export const createGlobalVariable = (payload) =>
  http.post('/global-variables', payload)

export const updateGlobalVariable = (id, payload) =>
  http.put(`/global-variables/${id}`, payload)

export const deleteGlobalVariable = (id) =>
  http.delete(`/global-variables/${id}`)

// ===== Scenarios =====
export const listScenarios = () =>
  http.get('/scenarios')

export const getScenario = (id) =>
  http.get(`/scenarios/${id}`)

export const createScenario = (payload) =>
  http.post('/scenarios', payload)

export const updateScenario = (id, payload) =>
  http.put(`/scenarios/${id}`, payload)

export const deleteScenario = (id) =>
  http.delete(`/scenarios/${id}`)

export const replaceScenarioSteps = (id, steps) =>
  http.put(`/scenarios/${id}/steps`, steps)

export const runScenario = (id, tenantId) =>
  http.post(`/scenarios/${id}/run`, null, { params: { tenantId } })

// V2: count of execution_history rows that ran as part of this scenario.
export const scenarioRelatedCounts = (id) =>
  http.get(`/scenarios/${id}/related-counts`)

// ===== Precheck =====
// The endpoint takes a @RequestBody Map, so always send a JSON body — a
// bodyless POST is rejected by Spring with 415.
export const runPrecheck = (scriptId, body = {}) =>
  http.post(`/scripts/${scriptId}/precheck`, body)

export const getPrecheck = (scriptId) =>
  http.get(`/scripts/${scriptId}/precheck`)

export const savePrecheck = (scriptId, payload) =>
  http.put(`/scripts/${scriptId}/precheck`, payload)

// ===== Dry Run =====
export const dryRun = (payload) =>
  http.post('/executions/preview', payload)

// ===== File Uploads =====
export const uploadFile = (file, onProgress) => {
  const fd = new FormData()
  fd.append('file', file)
  return http.post('/uploads', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: (e) => onProgress && onProgress(e)
  })
}

// ===== Batch =====
// Blocking like /executions: the server runs every row before answering.
export const runBatch = (payload) =>
  http.post('/batches/execute', payload, { timeout: 0 })

export const getBatch = (batchId) =>
  http.get(`/batches/${batchId}`)

// ===== Package Import / Export =====
export const exportScript = (id) =>
  http.get(`/scripts/${id}/export`, { responseType: 'blob' })

export const importScript = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return http.post('/scripts/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
