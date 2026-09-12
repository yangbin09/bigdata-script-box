import axios from 'axios'
import { ElMessage } from 'element-plus'

/*
 * Shared axios instance + response normalization.
 *
 * Contract (important — this is what every api/*.js module relies on):
 *   - JSON endpoints answer with { code, message, data }. This client resolves
 *     the *payload* (`data`) and rejects with an Error on `code !== 0`.
 *   - Non-JSON endpoints (stdout / stderr / export blobs) resolve the raw body.
 *   - The axios response object is never exposed, so callers must NOT reach for
 *     `.data` a second time.
 *
 * Before this was unified the interceptor resolved the whole wrapper while
 * api/*.js functions also did `.then(r => r.data)`, which made `r?.data` look
 * correct at the call site but silently yield `undefined` (broken 产物/结果 tabs
 * and dead "related counts" warnings). Keep the single unwrap here.
 */

// Default budget for ordinary REST calls. Executions are long-running and pass
// their own (script timeout + slack) via api/executions.js.
export const DEFAULT_TIMEOUT = 60000

const http = axios.create({
  baseURL: '/api',
  timeout: DEFAULT_TIMEOUT
})

// Identical toasts within this window collapse into one, so a failing poll or a
// burst of parallel requests can't stack up a wall of red messages.
const TOAST_DEDUPE_MS = 2000
let lastToast = { msg: '', at: 0 }

export function toastError(message) {
  const msg = message || '请求失败'
  const now = Date.now()
  if (msg === lastToast.msg && now - lastToast.at < TOAST_DEDUPE_MS) return
  lastToast = { msg, at: now }
  ElMessage.error(msg)
}

/** Best-effort human message for an axios failure. */
function messageOf(err) {
  const body = err?.response?.data
  if (body && typeof body === 'object' && typeof body.message === 'string' && body.message) {
    return body.message
  }
  if (typeof body === 'string' && body.trim()) return body.trim().slice(0, 200)
  if (err?.code === 'ECONNABORTED' || /timeout/i.test(err?.message || '')) {
    return '请求超时，请稍后重试'
  }
  if (!err?.response) return '网络错误，请检查服务是否在线'
  return `请求失败 (HTTP ${err.response.status})`
}

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    // Wrapped ApiResponse: unwrap the payload, surface errors as rejections.
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data
      toastError(body.message)
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    // text/plain stdout/stderr or a downloaded blob.
    return body
  },
  (err) => {
    toastError(messageOf(err))
    return Promise.reject(err)
  }
)

export default http
