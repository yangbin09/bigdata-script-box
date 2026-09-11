import axios from 'axios'
import { ElMessage } from 'element-plus'

// Same-origin in prod (single JAR), proxied to :80 in dev (Vite).
const http = axios.create({
  baseURL: '/api',
  timeout: 60000
})

// Backend wraps everything in { code, message, data }.
// Anything other than code===0 is a backend-reported error.
http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body
      ElMessage.error(body.message || 'request failed')
      return Promise.reject(new Error(body.message || 'request failed'))
    }
    // Non-wrapped responses (e.g. text/plain stdout/stderr).
    return resp
  },
  (err) => {
    const msg = err.response?.data?.message || err.message || 'network error'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default http