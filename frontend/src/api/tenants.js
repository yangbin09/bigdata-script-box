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

/**
 * V3 (PR-1): 把租户认证标记为"待重新测试"。
 *
 * <p>前端在用户改 principal/keytab 后调用，让 UI 能显示"Principal 或 Keytab
 * 已变更，请重新测试认证"横幅。服务端只清空 {@code lastTestAt} / {@code lastTestOk}。
 */
export const markTenantStale = (id) =>
  http.post(`/tenants/${id}/mark-stale`)
