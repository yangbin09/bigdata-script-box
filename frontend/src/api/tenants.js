import http from './http'

export const listTenants = () =>
  http.get('/tenants').then((r) => r.data)

export const getTenant = (id) =>
  http.get(`/tenants/${id}`).then((r) => r.data)

export const createTenant = (tenant) =>
  http.post('/tenants', tenant).then((r) => r.data)

export const updateTenant = (id, tenant) =>
  http.put(`/tenants/${id}`, tenant).then((r) => r.data)

export const deleteTenant = (id) =>
  http.delete(`/tenants/${id}`).then((r) => r.data)

export const setTenantEnabled = (id, enabled) =>
  http.post(`/tenants/${id}/enabled`, null, { params: { enabled } }).then((r) => r.data)

export const uploadKeytab = (id, file) => {
  const fd = new FormData()
  fd.append('file', file)
  return http.post(`/tenants/${id}/keytab`, fd, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }).then((r) => r.data)
}

export const testTenant = (id) =>
  http.post(`/tenants/${id}/test`).then((r) => r.data)

// V2: count of execution_history rows referencing this tenant.
export const tenantRelatedCounts = (id) =>
  http.get(`/tenants/${id}/related-counts`).then((r) => r.data)