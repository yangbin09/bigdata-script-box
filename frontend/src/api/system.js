import http from './http'

export const systemInfo = () =>
  http.get('/system/info')
