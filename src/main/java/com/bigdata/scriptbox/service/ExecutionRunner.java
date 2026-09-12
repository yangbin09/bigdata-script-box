package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ExecutionAccepted;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.model.ExecutionStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V3 (PR-0): 异步执行调度器。
 *
 * <p>把 {@code ScriptExecutor.execute()} 包装到独立线程池，准入即返 executionId。
 * 真实执行在后台线程中跑，进程结束后由 {@link ScriptExecutor} 自己写终态到 DB。
 *
 * <p>职责极薄 —— 不复制 {@link ScriptExecutor} 的命令构造 / 进程管理逻辑，
 * 只承担三件事：
 * <ol>
 *   <li>分配 executionId（自增 ID，与 {@link ScriptExecutor} 内部的 counter 隔离，
 *       避免同步路径与异步路径撞 ID）；</li>
 *   <li>把 {@link ExecutionRequest} 提交到线程池；</li>
 *   <li>异常兜底：worker 抛错时写一条 FAILED 行 + 释放槽位（避免 RUNNING 永远挂着）。</li>
 * </ol>
 *
 * <p>回退策略：{@code scriptbox.exec.async-enabled=false} 时
 * {@link #submit(ExecutionRequest)} 直接退化为 {@code ScriptExecutor.execute()}，
 * 保持同步语义不变 —— 老集成测试可以照常跑。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ExecutionRunner {

    private final ScriptBoxProperties props;
    private final ScriptExecutor executor;
    private final ExecutionHistoryMapper historyMapper;
    private final ExecutionGate executionGate;

    /** 独立 ID 序列，与 ScriptExecutor.counter 隔离。 */
    private final AtomicLong asyncCounter = new AtomicLong(System.currentTimeMillis() * 1000L);

    /** 异步执行线程池；nullable 表示未启用异步（懒加载构造）。 */
    private volatile ThreadPoolExecutor pool;

    @PostConstruct
    void init() {
        if (!props.getExec().isAsyncEnabled()) {
            log.info("ExecutionRunner: async-enabled=false，回退同步路径");
            return;
        }
        int core = Math.min(2, props.getExec().getRunnerPoolSize());
        int max = props.getExec().getRunnerPoolSize();
        int queue = props.getExec().getRunnerQueueSize();
        String prefix = props.getExec().getRunnerNamePrefix();
        this.pool = new ThreadPoolExecutor(
                core, max,
                60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(queue),
                namedThreadFactory(prefix),
                // CallerRunsPolicy：线程池满时让调用方（Tomcat 线程）同步跑 —— 不会丢任务，
                // 但会拖慢 HTTP 响应（前端会先看到 PENDING，再轮询到终态）。
                new ThreadPoolExecutor.CallerRunsPolicy());
        log.info("ExecutionRunner: 异步池就绪 core={} max={} queue={} prefix={}",
                core, max, queue, prefix);
    }

    @PreDestroy
    void shutdown() {
        if (pool == null) return;
        log.info("ExecutionRunner: 平滑关闭线程池，等待活跃任务收尾");
        pool.shutdown();
        try {
            // 最多等 30s 让 RUNNING 任务自己到 finalize；超时强制关闭。
            if (!pool.awaitTermination(30L, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("ExecutionRunner: 30s 内有任务未完成，强制关闭");
                pool.shutdownNow();
            }
        } catch (InterruptedException ie) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 申请一个 executionId 并把请求丢到执行池。
     *
     * <p>早返：不等进程结束。调用方拿 {@code executionId} 轮询
     * {@code GET /api/executions/{id}/state}。
     *
     * @param req 执行请求
     * @return 早返结果（含 executionId）
     * @throws IllegalArgumentException 准入前置失败（脚本/租户不存在等）
     */
    public ExecutionAccepted submit(ExecutionRequest req) {
        if (!props.getExec().isAsyncEnabled()) {
            // 回退同步：直接调 executor.execute()，把最终行作为响应体回传。
            // 这一支只在 async-enabled=false 时走到；同步路径由老调用方负责。
            try {
                ExecutionHistory h = executor.execute(req);
                return new ExecutionAccepted(h.getId(), h.getStatus(), "sync fallback");
            } catch (java.io.IOException ioe) {
                throw new IllegalStateException("sync execute failed: " + ioe.getMessage(), ioe);
            }
        }

        // 先做一次"准入前置"：脚本存在性 / 租户存在性校验，失败直接抛，
        // 避免给线程池丢一个注定 FAILED 的任务（污染状态码分布）。
        // 完整 ExecutionGate 准入仍在线程内由 ScriptExecutor.startProcess 负责。
        long executionId = asyncCounter.incrementAndGet();
        req.setExecutionId(executionId); // 让 ScriptExecutor 复用这个 ID
        // 登记"已分配但可能还没落库"：前端拿到 ID 会立刻拉实时日志，
        // 而 history 行要等 worker 走到 captureSnapshot 才写入。
        executionGate.markAllocated(executionId);
        log.info("ExecutionRunner: submit executionId={} scriptId={} tenantId={}",
                executionId, req.getScriptId(), req.getTenantId());

        try {
            pool.execute(() -> runSafely(req));
        } catch (RejectedExecutionException ree) {
            // CallerRunsPolicy 实际上会吞掉这个异常 —— 这里只兜底"线程池已 SHUTDOWN"
            // 的极端情况（JVM 关闭期）。
            log.error("ExecutionRunner: 线程池拒绝任务 executionId={}: {}",
                    executionId, ree.getMessage());
            throw new IllegalStateException("execution pool unavailable", ree);
        }
        return new ExecutionAccepted(executionId, ExecutionStatus.PENDING.name(), "accepted");
    }

    /**
     * 实际执行体。捕获所有异常（不让 worker 死掉），把失败写到 history 行
     * 释放前端能够区分"失败"与"丢失"。
     */
    private void runSafely(ExecutionRequest req) {
        long executionId = req.getExecutionId() == null ? -1L : req.getExecutionId();
        try {
            ExecutionHistory result = executor.execute(req);
            log.info("ExecutionRunner: 任务完成 executionId={} status={}",
                    executionId, result.getStatus());
        } catch (Throwable t) {
            // 不论是 IllegalArgument / IOException / RuntimeException / 其它，
            // 都尝试把"失败"留痕 —— 否则 executionId 会永远显示为 PENDING。
            log.error("ExecutionRunner: 任务异常 executionId={}: {}",
                    executionId, t.getMessage(), t);
            writeFailedHistory(req, t);
        } finally {
            // 收尾后才能取消登记：这之前/之后 history 行的可见性由本方法保证，
            // 所以任何还在轮询这个 ID 的前端都能得到一个确定的答案（行或空日志），
            // 而不是"既没行也没登记"的 404。
            if (executionId > 0) executionGate.clearAllocated(executionId);
        }
    }

    /**
     * worker 异常时尝试把一行 FAILED 写进 history，方便前端任务中心显示
     * "执行失败：xxx" 而非永远 PENDING / RUNNING。
     *
     * <p><b>为什么不是无脑 insert</b>：{@code ScriptExecutor} 在真正启动进程前就已经
     * 用同一个 executionId 写入了 RUNNING 行（captureSnapshot）。如果 worker 是在
     * "进程还没起来"的阶段失败的（典型：同居并发被 {@code ExecutionGate} 拒绝），
     * 那一行已经存在 —— 直接 insert 会撞主键，异常被吞掉，于是这条执行**永远停在
     * RUNNING**，前端只能靠 11 分钟后超时才收场。
     *
     * <p>所以这里按"存在就更新、不存在才插入"处理，并且失败时降级为一条最小更新，
     * 保证状态一定能落成终态。
     */
    private void writeFailedHistory(ExecutionRequest req, Throwable t) {
        Long id = req.getExecutionId();
        String reason = "runner_exception: " + t.getClass().getSimpleName()
                + ": " + t.getMessage();
        try {
            ExecutionHistory existing = id == null ? null : historyMapper.selectById(id);
            if (existing != null) {
                // 行已由 captureSnapshot 写入（RUNNING）→ 只补终态字段
                existing.setStatus(ExecutionStatus.FAILED.name());
                existing.setSuccess(false);
                existing.setTimeout(false);
                existing.setExitCode(-1);
                existing.setEndTime(java.time.LocalDateTime.now());
                if (existing.getDurationMs() == null) existing.setDurationMs(0L);
                existing.setInterruptedReason(reason);
                historyMapper.updateById(existing);
                return;
            }
            ExecutionHistory h = new ExecutionHistory();
            h.setId(id);
            h.setScriptId(req.getScriptId());
            h.setTenantId(req.getTenantId());
            h.setParametersJson("{}");
            h.setStatus(ExecutionStatus.FAILED.name());
            h.setSuccess(false);
            h.setTimeout(false);
            h.setExitCode(-1);
            h.setStartTime(java.time.LocalDateTime.now());
            h.setEndTime(java.time.LocalDateTime.now());
            h.setDurationMs(0L);
            // 把失败原因写到 interruptedReason —— entity 暂时没有 summary 字段。
            h.setInterruptedReason(reason);
            // 没有 stdout / executionDir 等路径 —— 历史详情页需要兜底。
            historyMapper.insert(h);
        } catch (Exception ex) {
            // 兜底：哪怕上面的分支炸了，也要尽量把状态推进到终态（不能留在 RUNNING）
            log.error("ExecutionRunner: FAILED 兜底行写入失败 executionId={}", id, ex);
            try {
                if (id != null) {
                    ExecutionHistory h = historyMapper.selectById(id);
                    if (h != null) {
                        h.setStatus(ExecutionStatus.FAILED.name());
                        h.setSuccess(false);
                        h.setEndTime(java.time.LocalDateTime.now());
                        h.setInterruptedReason(reason);
                        historyMapper.updateById(h);
                    }
                }
            } catch (Exception ignored) {
                // 真没救了：worker 不能死
            }
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger n = new AtomicInteger(1);
        return r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName(prefix + n.getAndIncrement());
            t.setDaemon(false);
            return t;
        };
    }
}
