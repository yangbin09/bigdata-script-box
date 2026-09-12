import { onUnmounted, ref } from 'vue'
import { executionState, logTail } from '../api/executions'

const TERMINAL = ['SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED', 'PRECHECK_FAILED', 'INTERRUPTED']
const POLL_MS = 1500
const TAIL_MS = 2000

/**
 * 跟踪一次执行的进行时状态：实时 tail stdout + 轮询终态。
 *
 * <p><b>为什么不用 executionStore 的订阅</b>：store 的 `refreshActive()` 会在执行
 * 结束后把 id 从 `activeIds` 摘掉，而 per-id 的 `/state` 轮询只在 `activeIds` 里
 * 遍历 —— 对「1ms 就结束」的脚本，`/state` 从未被请求过，靠 store 视图等终态会
 * 永远等不到，结果面板因此不渲染（这正是「执行日志为空」的真实原因）。
 * 所以这里直接轮询后端，不依赖任何前端缓存节奏。
 *
 * <p>失败与"真的没有输出"分开表达：`error` 非空表示读取失败，UI 必须显式提示，
 * 不能退化成「无 stdout 输出」。
 *
 * @param {{ intervalMs?: number, tailMs?: number }} [options]
 */
export function useLiveLog(options = {}) {
  const tailInterval = options.tailMs ?? TAIL_MS

  /** 最新 tail 到的 stdout 文本 */
  const text = ref('')
  /** 非空 = 读取失败原因（不是"没有输出"） */
  const error = ref(false)
  /** 执行中 */
  const running = ref(false)
  /** 执行 ID */
  const executionId = ref(null)

  let tailTimer = null
  let pollTimer = null
  let guardTimer = null
  let done = true

  async function refreshTail() {
    const id = executionId.value
    if (!id || !running.value) return
    try {
      const chunk = await logTail(id, 'stdout', 65536)
      error.value = false
      if (typeof chunk === 'string' && chunk !== text.value) text.value = chunk
    } catch (_) {
      error.value = true
    }
  }

  function startTail() {
    stopTail()
    tailTimer = setInterval(refreshTail, tailInterval)
    // 立即拉一次，别让用户干等一个周期
    refreshTail()
  }

  function stopTail() {
    if (tailTimer) { clearInterval(tailTimer); tailTimer = null }
  }

  function stopPoll() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
    if (guardTimer) { clearTimeout(guardTimer); guardTimer = null }
  }

  /**
   * 开始跟踪。
   *
   * @param {number|string} id 执行 ID
   * @param {number} timeoutMs 最长等待；到点仍未终态也 resolve（由调用方决定怎么展示）
   * @returns {Promise<void>} 进入终态（或超时）后 resolve
   */
  function track(id, timeoutMs) {
    stop()
    executionId.value = id
    text.value = ''
    error.value = false
    running.value = true
    done = false
    startTail()

    return new Promise((resolve) => {
      const deadline = Date.now() + timeoutMs
      let lastStatus = null
      let stableHits = 0

      function finish() {
        if (done) return
        done = true
        stopPoll()
        stopTail()
        running.value = false
        resolve()
      }

      /**
       * 终态判定有两道保险，避免"读到还没写完的行"：
       *
       * <p>1) 状态是终态**且**退出码已落库。被并发拒绝的执行为「立刻终态」，
       * 前端可能恰好在兜底逻辑写完之前就读到了中间态（exitCode=null），
       * 于是结果面板显示 "Exit -1 / 无 stdout 输出"，而真正原因（已被拒绝）
       * 还没写进那一行。等数据写稳再收尾。
       *
       * <p>2) 连续两次看到同一个终态才收尾，规避"读到一半又被改写"的窗口。
       * 取消失败没有可靠的 exitCode 语义，直接认。
       */
      async function poll() {
        if (done) return
        let status = null
        let settled = false
        try {
          const view = await executionState(id)
          status = view?.state ?? null
          if (status && TERMINAL.includes(status)) {
            const noExitCodeExpected = view.cancelled === true || status === 'CANCELLED'
            settled = noExitCodeExpected || view.exitCode != null
          }
        } catch (_) { /* 单次失败继续轮询；终态判定不因一次网络抖动放弃 */ }

        if (settled) {
          if (status === lastStatus) {
            stableHits += 1
            if (stableHits >= 2) { finish(); return }
          } else {
            lastStatus = status
            stableHits = 1
          }
        } else {
          lastStatus = null
          stableHits = 0
        }

        if (Date.now() > deadline) finish()
      }

      poll()
      pollTimer = setInterval(poll, POLL_MS)
      guardTimer = setTimeout(finish, timeoutMs + 1000)
    })
  }

  /** 停止跟踪（关闭抽屉 / 组件卸载）：只停前端，不影响后端执行。 */
  function stop() {
    done = true
    stopPoll()
    stopTail()
    running.value = false
  }

  onUnmounted(stop)

  return { text, error, running, executionId, track, stop, refreshTail }
}
