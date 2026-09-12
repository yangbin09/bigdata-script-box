import http from './http'

// V2: list built-in script templates the user can pick from when creating
// a new script. The response includes content + paramsJson — the FE pre-
// populates the edit form, then POSTs /api/scripts/from-template.
export const listTemplates = () =>
  http.get('/script-templates')

export const getTemplate = (code) =>
  http.get(`/script-templates/${code}`)

// Create a real Script from a built-in template. The user picks a unique
// name; the server copies the template's content + paramsJson into a new
// Script row, which is then editable through the normal edit flow.
export const createFromTemplate = (templateCode, name) =>
  http.post('/scripts/from-template', { templateCode, name })
