package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 批量执行服务：用同一脚本对多组参数各跑一次，每组参数产生一条
 * {@link ExecutionHistory}，共享同一个 batchId。
 *
 * <p>默认顺序执行：脚本执行器调用较慢且可能启子进程，避免批量并发把宿主机打爆；
 * 当 {@code concurrency > 1} 时启用并行模式，并发上限由
 * {@link ScriptBoxProperties#getMaxBatchConcurrency()} 控制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchService {

    private final ScriptExecutor executor;
    private final ExecutionHistoryMapper historyMapper;
    private final ScriptBoxProperties props;

    /** 单调递增的 batchId 计数器（基于当前毫秒时间戳起步）。 */
    private final AtomicLong batchCounter = new AtomicLong(System.currentTimeMillis() * 1000L);

    /** 批量执行汇总（成功 / 失败 / 涉及到的 executionId 列表）。 */
    public record BatchSummary(
            String batchId,
            int total,
            int succeeded,
            int failed,
            List<Long> historyIds
    ) {
        /**
         * 累积式构造器：调用方逐行 add 成功 / 失败计数 / historyId，最后一次性 build。
         */
        public static class Builder {
            private String batchId;
            private int total;
            private int succeeded;
            private int failed;
            private final List<Long> historyIds = new ArrayList<>();

            public Builder batchId(String v) { this.batchId = v; return this; }
            public Builder total(int v) { this.total = v; return this; }
            public synchronized Builder addSuccess(long historyId) { this.succeeded++; this.historyIds.add(historyId); return this; }
            public synchronized Builder addFailure() { this.failed++; return this; }
            public synchronized BatchSummary build() { return new BatchSummary(batchId, total, succeeded, failed, List.copyOf(historyIds)); }
        }
    }

    /**
     * 顺序执行一批：每行一个 ExecutionHistory，单行失败不影响其它行。
     */
    public BatchSummary runSequential(Long scriptId, Long tenantId, Long presetId,
                                      List<Map<String, String>> rows) {
        return runSequential(scriptId, tenantId, presetId, rows, null);
    }

    /**
     * 顺序执行（带确认 token）。当脚本被标记为 DANGEROUS 时，
     * 每行的 {@link ExecutionRequest} 都会带上 confirmToken 以便执行器判断。
     */
    public BatchSummary runSequential(Long scriptId, Long tenantId, Long presetId,
                                      List<Map<String, String>> rows, String confirmToken) {
        validateRows(rows);

        String batchId = "batch-" + batchCounter.incrementAndGet();
        BatchSummary.Builder builder = new BatchSummary.Builder()
                .batchId(batchId)
                .total(rows.size());

        log.info("batch: 顺序执行开始 batchId={} scriptId={} rows={}", batchId, scriptId, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            ExecutionRequest req = new ExecutionRequest();
            req.setScriptId(scriptId);
            req.setTenantId(tenantId);
            req.setPresetId(presetId);
            req.setParams(row);
            req.setBatchId(batchId);
            req.setBatchRowIndex(i);
            req.setConfirmToken(confirmToken);
            try {
                ExecutionHistory h = executor.execute(req);
                if (Boolean.TRUE.equals(h.getSuccess())) builder.addSuccess(h.getId());
                else builder.addFailure();
            } catch (Exception ex) {
                log.warn("batch {} row {} failed: {}", batchId, i, ex.getMessage());
                builder.addFailure();
            }
        }
        BatchSummary summary = builder.build();
        log.info("batch: 顺序执行完成 batchId={} succeeded={} failed={}",
                batchId, summary.succeeded(), summary.failed());
        return summary;
    }

    /**
     * 并行执行一批：仍然共享 batchId，concurrency 上限 8。
     */
    public BatchSummary runParallel(Long scriptId, Long tenantId, Long presetId,
                                    List<Map<String, String>> rows, int concurrency) {
        return runParallel(scriptId, tenantId, presetId, rows, concurrency, null);
    }

    /**
     * 并行执行（带确认 token），confirmToken 语义同顺序版。
     */
    public BatchSummary runParallel(Long scriptId, Long tenantId, Long presetId,
                                    List<Map<String, String>> rows, int concurrency, String confirmToken) {
        validateRows(rows);
        if (concurrency <= 0) concurrency = 1;
        if (concurrency > props.getMaxBatchConcurrency()) concurrency = props.getMaxBatchConcurrency();

        String batchId = "batch-" + batchCounter.incrementAndGet();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency,
                r -> {
                    Thread t = new Thread(r, "batch-" + batchId);
                    t.setDaemon(true);
                    return t;
                });
        BatchSummary.Builder builder = new BatchSummary.Builder()
                .batchId(batchId)
                .total(rows.size());

        log.info("batch: 并行执行开始 batchId={} scriptId={} rows={} concurrency={}",
                batchId, scriptId, rows.size(), concurrency);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < rows.size(); i++) {
                final int idx = i;
                final Map<String, String> row = rows.get(i);
                futures.add(pool.submit(() -> {
                    ExecutionRequest req = new ExecutionRequest();
                    req.setScriptId(scriptId);
                    req.setTenantId(tenantId);
                    req.setPresetId(presetId);
                    req.setParams(row);
                    req.setBatchId(batchId);
                    req.setBatchRowIndex(idx);
                    req.setConfirmToken(confirmToken);
                    try {
                        ExecutionHistory h = executor.execute(req);
                        if (Boolean.TRUE.equals(h.getSuccess())) builder.addSuccess(h.getId());
                        else builder.addFailure();
                    } catch (Exception ex) {
                        log.warn("batch {} row {} failed: {}", batchId, idx, ex.getMessage());
                        builder.addFailure();
                    }
                }));
            }
            // 等待所有 worker 结束：每个 worker 内部已 try/catch，f.get() 主要作用是
            // 阻塞等完成。即便 worker 抛了未预期异常（理论上 worker body 已尽量收口），
            // 这里也要记录而不是默默吞掉；InterruptedException 需要恢复中断标志。
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("batch {} await 中断", batchId);
                } catch (Exception ex) {
                    log.error("batch {} worker 未捕获异常: {}", batchId, ex.getMessage(), ex);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        BatchSummary summary = builder.build();
        log.info("batch: 并行执行完成 batchId={} succeeded={} failed={}",
                batchId, summary.succeeded(), summary.failed());
        return summary;
    }

    /**
     * 查一个 batch 下所有 ExecutionHistory 行（按 batchRowIndex 升序）。
     */
    public List<ExecutionHistory> findByBatch(String batchId) {
        return historyMapper.selectByBatchId(batchId);
    }

    /**
     * 把 ExecutionHistory 列表压缩成 UI 友好的字段（id / scriptName / tenantName /
     * success / status / exitCode / startTime / durationMs / batchRowIndex）。
     */
    public List<Map<String, Object>> summarize(List<ExecutionHistory> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExecutionHistory h : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.getId());
            m.put("scriptName", h.getScriptName());
            m.put("tenantName", h.getTenantName());
            m.put("success", h.getSuccess());
            m.put("status", h.getStatus());
            m.put("exitCode", h.getExitCode());
            m.put("startTime", h.getStartTime());
            m.put("durationMs", h.getDurationMs());
            m.put("batchRowIndex", h.getBatchRowIndex());
            out.add(m);
        }
        return out;
    }

    /**
     * 校验入参 rows 非空且不超过 {@link ScriptBoxProperties#getMaxBatchRows()}。
     * 由 {@link #runSequential} 与 {@link #runParallel} 共享，避免重复两遍手写判断。
     */
    private void validateRows(List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("empty rows");
        int max = props.getMaxBatchRows();
        if (rows.size() > max) throw new IllegalArgumentException(
                "too many rows: " + rows.size() + " > " + max);
    }
}