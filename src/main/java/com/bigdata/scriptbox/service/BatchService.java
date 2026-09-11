package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
 * 当 {@code concurrency > 1} 时启用并行模式，最多 8 路并发。
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    /** 硬上限，避免失控批次。 */
    private static final int MAX_ROWS = 200;

    @Autowired private ScriptExecutor executor;
    @Autowired private com.bigdata.scriptbox.mapper.ExecutionHistoryMapper historyMapper;

    /** 单调递增的 batchId 计数器（基于当前毫秒时间戳起步）。 */
    private final AtomicLong batchCounter = new AtomicLong(System.currentTimeMillis() * 1000L);

    /** 批量执行汇总（成功 / 失败 / 涉及到的 executionId 列表）。 */
    public static class BatchSummary {
        public String batchId;
        public int total;
        public int succeeded;
        public int failed;
        public List<Long> historyIds = new ArrayList<>();
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
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("empty rows");
        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException(
                "too many rows: " + rows.size() + " > " + MAX_ROWS);

        String batchId = "batch-" + batchCounter.incrementAndGet();
        BatchSummary summary = new BatchSummary();
        summary.batchId = batchId;
        summary.total = rows.size();

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
                summary.historyIds.add(h.getId());
                if (Boolean.TRUE.equals(h.getSuccess())) summary.succeeded++;
                else summary.failed++;
            } catch (Exception ex) {
                log.warn("batch {} row {} failed: {}", batchId, i, ex.getMessage());
                summary.failed++;
            }
        }
        log.info("batch: 顺序执行完成 batchId={} succeeded={} failed={}", batchId, summary.succeeded, summary.failed);
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
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("empty rows");
        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException(
                "too many rows: " + rows.size() + " > " + MAX_ROWS);
        if (concurrency <= 0) concurrency = 1;
        if (concurrency > 8) concurrency = 8;

        String batchId = "batch-" + batchCounter.incrementAndGet();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency,
                r -> {
                    Thread t = new Thread(r, "batch-" + batchId);
                    t.setDaemon(true);
                    return t;
                });
        BatchSummary summary = new BatchSummary();
        summary.batchId = batchId;
        summary.total = rows.size();

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
                        // historyIds + succeeded/failed 必须在同一把锁内更新，避免
                        // 出现"succeeded 计数加了但 historyIds 没加"的不一致状态。
                        synchronized (summary) {
                            summary.historyIds.add(h.getId());
                            if (Boolean.TRUE.equals(h.getSuccess())) summary.succeeded++;
                            else summary.failed++;
                        }
                    } catch (Exception ex) {
                        log.warn("batch {} row {} failed: {}", batchId, idx, ex.getMessage());
                        synchronized (summary) {
                            summary.failed++;
                        }
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
        log.info("batch: 并行执行完成 batchId={} succeeded={} failed={}",
                batchId, summary.succeeded, summary.failed);
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
}