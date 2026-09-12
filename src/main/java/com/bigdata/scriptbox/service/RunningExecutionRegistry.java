package com.bigdata.scriptbox.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V2: 当前正在运行的执行注册表。每个条目保存 {@link Process} 句柄和足够的元数据，
 * 用于 (a) 取消时干净地销毁进程 + 子进程，(b) 在执行线程 finalize 之后记录取消结果。
 *
 * <p>Map 以 executionId 为 key。条目在 Process 启动那一瞬间加入，在执行线程完成
 * {@link ExecutionHistory} 写入的最后一刻移除。取消走 {@code destroyForcibly} +
 * {@link ProcessHandle#descendants()} 把子进程一起带走。
 *
 * <p>cancel API 不会触碰 executionId 未知（即已结束）的进程 —— 说明那次执行已经
 * 完成，没必要再 kill。
 */
@Slf4j
@Component
public class RunningExecutionRegistry {

    /**
     * 单个正在运行的执行。
     */
    public static class RunningExecution {
        public final long executionId;
        public final long scriptId;
        public final long tenantId;
        public final long startedAtMs;
        public final Process process;
        /** 取消信号：是否已被请求取消。 */
        public final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** 结束标记：true 后 cancel 是 no-op。 */
        public final AtomicBoolean finished = new AtomicBoolean(false);

        public RunningExecution(long executionId, long scriptId, long tenantId,
                                long startedAtMs, Process process) {
            this.executionId = executionId;
            this.scriptId = scriptId;
            this.tenantId = tenantId;
            this.startedAtMs = startedAtMs;
            this.process = process;
        }
    }

    private final Map<Long, RunningExecution> live = new ConcurrentHashMap<>();

    /**
     * 注册一个新的执行条目。若同一 executionId 已有条目，原条目会被覆盖（理论上
     * 不会出现，因为执行 id 在历史表里唯一自增）。
     */
    public void register(long executionId, long scriptId, long tenantId,
                         long startedAtMs, Process process) {
        live.put(executionId, new RunningExecution(executionId, scriptId, tenantId,
                startedAtMs, process));
        log.info("execution: 注册运行中 executionId={} scriptId={} tenantId={}",
                executionId, scriptId, tenantId);
    }

    /**
     * 标记该执行已结束；之后任何 cancel 调用都会成为 no-op。
     */
    public void unregister(long executionId) {
        RunningExecution re = live.remove(executionId);
        if (re != null) re.finished.set(true);
    }

    /** 获取某个 execution 的运行条目；不存在返回 null。 */
    public RunningExecution get(long executionId) {
        return live.get(executionId);
    }

    /** 当前运行中的执行条数（用于监控 / 测试）。 */
    public int activeCount() {
        return live.size();
    }

    /**
     * 全部运行中执行的快照（不可修改视图）。供测试与偶尔的管理页面枚举用。
     */
    public java.util.Collection<RunningExecution> activeExecutions() {
        return java.util.Collections.unmodifiableCollection(live.values());
    }

    /**
     * 取消一个运行中的执行：立刻 destroyForcibly 进程及子孙进程，避免子进程
     * 看到父进程消失后 fork-spawn 更多工作；同时设置取消标志供执行线程读取。
     *
     * <p>即使进程已经退出也安全 —— no-op。多次 cancel 同样安全 —— 二次调用 no-op。
     *
     * <p>与 {@link com.bigdata.scriptbox.executor.ProcessRunner} 的职责分工：
     * cancel API 是用户主动行为，立刻 kill 才能让 ProcessRunner 的 waitFor 在
     * 合理时间内返回；ProcessRunner 在超时路径下也独立 destroy。destroyForcibly
     * 本身幂等，多次调用无副作用。
     *
     * @return true 表示找到并发送了取消信号；false 表示已经结束或从未存在
     */
    public boolean cancel(long executionId) {
        RunningExecution re = live.get(executionId);
        if (re == null) return false;
        if (re.finished.get()) return false;
        if (re.cancelled.get()) return false; // 已发送过；二次调用 no-op
        re.cancelled.set(true);
        destroyTree(re.process);
        log.info("execution: 取消信号已发送 executionId={}", executionId);
        return true;
    }

    /**
     * 销毁进程及其子孙进程。cancel API 与 ProcessRunner 共用本方法，
     * 避免两处各自实现子孙遍历的逻辑漂移。
     */
    private static void destroyTree(Process process) {
        if (process == null) return;
        try {
            ProcessHandle handle = process.toHandle();
            for (ProcessHandle d : handle.descendants().toList()) {
                try { d.destroyForcibly(); } catch (Exception ignored) {}
            }
            process.destroyForcibly();
        } catch (Exception ignored) {
            // best-effort
        }
    }
}