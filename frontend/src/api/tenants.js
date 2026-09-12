import http from './http'

export const listTenants = () =>
  http.get('/tenants')

export const getTenant = (id) =>
  http.get(`/tenants/${id}`)

export const createTenant = (tenant) =>
  http.post('/tenants', tenant)

export const updateTenant = (id, tenant) =>
  http.put(`/tenants/${id}`, tenant)

export const deleteTenant = (id) =>
  http.delete(`/tenants/${id}`)

export const setTenantEnabled = (id, enabled) =>
  http.post(`/tenants/${id}/enabled`, null, { params: { enabled } })

export const uploadKeytab = (id, file) => {
  const fd = new FormData()
  fd.append('file', file)
  return http.post(`/tenants/${id}/keytab`, fd, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export const testTenant = (id) =>
  http.post(`/tenants/${id}/test`)

// V2: count of execution_history rows referencing this tenant.
export const tenantRelatedCounts = (id) =>
  http.get(`/tenants/${id}/related-counts`)
