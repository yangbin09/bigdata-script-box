import http from './http'

export const listHistory = (params = {}) =>
  http.get('/history', { params })

export const getHistory = (id) =>
  http.get(`/history/${id}`)

export const recentScripts = (limit = 6) =>
  http.get('/history/recent-scripts', { params: { limit } })

/**
 * 「这个脚本上次成功时是怎么跑的」—— 由执行历史推导，而不是用户手动存的方案。
 *
 * <p>任何一次成功执行的参数天然就是一套可用方案，所以「重跑上次」不需要
 * 用户先创建 Preset。没有任何成功历史时后端返回 `parameters: {}`，
 * 前端据此回退到默认值。
 *
 * @param {number} scriptId
 * @param {number|null} tenantId 可选：限定在该租户的历史里找
 */
export const lastPlan = (scriptId, tenantId = null) => {
  // 只带上真正有值的参数：`tenantId=null` 这种字符串会让 Spring 的
  // Long 转换抛 MethodArgumentTypeMismatchException。
  const params = { scriptId }
  if (tenantId != null && tenantId !== '') params.tenantId = tenantId
  return http.get('/history/plan', { params })
}

/**
 * 按「脚本 + 天」聚合的历史概览（取代 200 条流水）。
 * 回答的是「哪个脚本最近失败过」，而不是「第 137 行是什么」。
 *
 * @param {number} days 回看天数（默认 7）
 */
export const historyDigest = (days = 7) =>
  http.get('/history/digest', { params: { days } })
