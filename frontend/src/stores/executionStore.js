import { defineStore } from 'pinia'
import * as executionsApi from '../api/executions'

/**
 * V3 (PR-0): 任务中心 + 异步执行状态管理。
 *
 * <p>核心字段：
 * <ul>
 *   <li>{@code byId} — executionId → 执行视图快照；切页 / 刷新都从这里恢复；</li>
 *   <li>{@code activeIds} — 当前活跃的执行 ID 列表（按 startTime 升序），驱动顶栏角标；</li>
 *   <li>{@code polling} — 正在轮询的 id 集合，避免重复 setInterval；</li>
 *   <li>{@code networkOnline} — 离线检测，配合 App.vue 顶栏的 NetworkBanner；</li>
 *   <li>{@code recentHistory} — 最近 10 条已结束的执行（任务中心右侧列表用）。</li>
 * </ul>
 *
 * <p>复用原则：所有"提交 / 跟踪 / 取消执行"的入口都走本 store，
 * 后续 PR（参数草稿、ExecuteDrawer、任务中心、⌘K）都从 {@code useExecutionStore}
 * 拿数据；不再有页面内独立的轮询定时器。
 */
export const useExecutionStore = defineStore('execution', {
  state: () => ({
    /** @type {Map<number, ExecutionView>} */
    byId: new Map(),
    /** @type {number[]} */
    activeIds: [],
    /** @type {Set<number>} */
    polling: new Set(),
    /** 后端是否可达；false 时顶栏显示"连接中断，正在恢复" */
    networkOnline: true,
    /** 最近 10 条已结束的执行，按 endTime desc */
    recentHistory: [],
    /** 主轮询定时器 id */
    pollTimer: null,
    /** 上次成功轮询的 ms 时间戳 */
    lastSuccessPollAt: 0,
    /** 离线起始时间（用于"已离线 N 秒"显示） */
    offlineSince: 0
  }),

  getters: {
    /** 顶栏角标显示：activeIds 数量 */
    activeCount: (state) => state.activeIds.length,
    /** 拿到一个执行视图（响应式） */
    getById: (state) => (id) => state.byId.get(Number(id)),
    /** 排序后的活跃列表（按 startTime asc —— 最老的在前，符合"先进先出"） */
    sortedActive: (state) => {
      return state.activeIds
        .map((id) => state.byId.get(id))
        .filter(Boolean)
        .sort((a, b) => (a.startedAtMs || 0) - (b.startedAtMs || 0))
    }
  },

  actions: {
    /**
     * 应用启动时调用一次：拉一次 recent-active，初始化 byId / activeIds，
     * 然后启动 setInterval 持续轮询活跃任务。
     */
    async bootstrap() {
      if (this.pollTimer) return // 幂等
      await this.refreshActive()
      // 每 2s 轮询一次
      this.pollTimer = setInterval(() => this.tick(), 2000)
    },

    /**
     * 单次 tick：拉 recent-active + 拉每个 active 的 /state 决定是否进入终态。
     * 终态后从 activeIds 移除，写入 recentHistory。
     */
    async tick() {
      if (!this.networkOnline) {
        // 离线时只探活一次
        try {
          await executionsApi.recentActive()
          this.markOnline()
          await this.refreshActive()
        } catch (e) {
          // 仍离线，保持 offlineSince
          return
        }
      }
      // 1) 同步活跃列表
      await this.refreshActive()
      // 2) 拉每个 active 的最新状态
      for (const id of [...this.activeIds]) {
        try {
          const res = await executionsApi.executionState(id)
          const view = res.data
          this.upsertView({
            id: view.id,
            scriptId: view.scriptId,
            tenantId: view.tenantId,
            status: view.state,
            startedAtMs: view.startedAtMs,
            exitCode: view.exitCode,
            cancelled: view.cancelled
          })
          if (this.isTerminal(view.state)) {
            this.markFinished(id, view.state, view.exitCode)
          }
        } catch (e) {
          // 单条失败不影响其他
        }
      }
    },

    async refreshActive() {
      try {
        const res = await executionsApi.recentActive()
        const list = res.data || []
        // 重建 activeIds
        const nextActive = new Set()
        for (const v of list) {
          if (this.isTerminal(v.status)) continue
          nextActive.add(v.id)
          this.upsertView({
            id: v.id,
            scriptId: v.scriptId,
            tenantId: v.tenantId,
            status: v.status,
            startedAtMs: v.startedAtMs,
            cancelled: v.cancelled
          })
        }
        // 移除已经不在活跃列表的（说明 DB 已经是终态，但 tick 还没扫到）
        for (const id of [...this.activeIds]) {
          if (!nextActive.has(id)) {
            // 注意：不在这里 markFinished，留给 tick() 的 /state 调用决定终态
            this.activeIds = this.activeIds.filter((x) => x !== id)
          }
        }
        // 加入新出现的
        for (const id of nextActive) {
          if (!this.activeIds.includes(id)) this.activeIds.push(id)
        }
        this.lastSuccessPollAt = Date.now()
        this.markOnline()
      } catch (e) {
        this.markOffline()
      }
    },

    /**
     * 提交一次执行（异步路径）。
     * 返回 executionId。worker 自己完成后续状态轮询。
     */
    async submit(req) {
      try {
        const res = await executionsApi.submitExecution(req)
        const data = res.data
        // 同步回退路径：后端直接返回 ExecutionHistory（status 已是终态）
        if (data && data.scriptId != null && data.status && !data.executionId) {
          this.upsertView({
            id: data.id,
            scriptId: data.scriptId,
            tenantId: data.tenantId,
            status: data.status,
            exitCode: data.exitCode,
            startedAtMs: data.startTime
              ? new Date(data.startTime).getTime()
              : Date.now()
          })
          this.markFinished(data.id, data.status, data.exitCode)
          return data.id
        }
        // 异步路径：早返 {executionId, status:'PENDING'}
        this.upsertView({
          id: data.executionId,
          scriptId: req.scriptId,
          tenantId: req.tenantId,
          status: data.status || 'PENDING',
          startedAtMs: Date.now()
        })
        if (!this.activeIds.includes(data.executionId)) {
          this.activeIds.push(data.executionId)
        }
        this.markOnline()
        return data.executionId
      } catch (e) {
        this.markOffline()
        throw e
      }
    },

    async cancel(id) {
      try {
        await executionsApi.cancelExecution(id)
        // cancel 后下一次 tick 会拉到终态 CANCELLED，markFinished 自动收尾
        this.upsertView({
          id,
          cancelled: true
        })
        this.markOnline()
      } catch (e) {
        this.markOffline()
        throw e
      }
    },

    upsertView(view) {
      const id = Number(view.id)
      const existing = this.byId.get(id) || {}
      this.byId.set(id, { ...existing, ...view })
    },

    markFinished(id, status, exitCode) {
      this.upsertView({ id, status, exitCode })
      this.activeIds = this.activeIds.filter((x) => x !== Number(id))
      this.polling.delete(Number(id))
      // 写进 recentHistory（最近 10 条）
      const view = this.byId.get(Number(id))
      if (view) {
        this.recentHistory = [
          { ...view, finishedAt: Date.now() },
          ...this.recentHistory.filter((x) => x.id !== Number(id))
        ].slice(0, 10)
      }
    },

    markOnline() {
      if (!this.networkOnline) {
        this.networkOnline = true
        this.offlineSince = 0
      }
    },

    markOffline() {
      if (this.networkOnline) {
        this.networkOnline = false
        this.offlineSince = Date.now()
      }
    },

    isTerminal(status) {
      return ['SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED', 'PRECHECK_FAILED', 'INTERRUPTED']
        .includes(status)
    }
  }
})
