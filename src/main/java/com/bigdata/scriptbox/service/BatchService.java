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
 * Run the same script multiple times against different parameter rows.
 * Each row produces one ExecutionHistory row sharing a single batchId.
 *
 * Rows run sequentially by default (script executor calls can be slow and we
 * don't want to fork-bomb the box). The simple "submit all at once" path is
 * still available when concurrency > 1.
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);
    private static final int MAX_ROWS = 200; // hard cap to avoid runaway batches

    @Autowired private ScriptExecutor executor;
    @Autowired private com.bigdata.scriptbox.mapper.ExecutionHistoryMapper historyMapper;

    private final AtomicLong batchCounter = new AtomicLong(System.currentTimeMillis() * 1000L);

    public static class BatchSummary {
        public String batchId;
        public int total;
        public int succeeded;
        public int failed;
        public List<Long> historyIds = new ArrayList<>();
    }

    /**
     * Run a batch sequentially: each row gets its own ExecutionHistory; one fails
     * does not abort the others.
     */
    public BatchSummary runSequential(Long scriptId, Long tenantId, Long presetId,
                                      List<Map<String, String>> rows) {
        return runSequential(scriptId, tenantId, presetId, rows, null);
    }

    /** V2: confirmToken is forwarded to every row's ExecutionRequest so that
     *  a DANGEROUS script's batch execution honours the operator's CONFIRM. */
    public BatchSummary runSequential(Long scriptId, Long tenantId, Long presetId,
                                      List<Map<String, String>> rows, String confirmToken) {
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("empty rows");
        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException(
                "too many rows: " + rows.size() + " > " + MAX_ROWS);

        String batchId = "batch-" + batchCounter.incrementAndGet();
        BatchSummary summary = new BatchSummary();
        summary.batchId = batchId;
        summary.total = rows.size();

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
        return summary;
    }

    /**
     * Run a batch with a small concurrency. Each row is still tracked under the
     * same batchId. Failures don't abort others.
     */
    public BatchSummary runParallel(Long scriptId, Long tenantId, Long presetId,
                                    List<Map<String, String>> rows, int concurrency) {
        return runParallel(scriptId, tenantId, presetId, rows, concurrency, null);
    }

    /** V2: see {@link #runSequential(Long, Long, Long, List, String)} for confirmToken semantics. */
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

        List<Future<?>> futures = new ArrayList<>();
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
                    synchronized (summary.historyIds) {
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
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
        pool.shutdownNow();
        return summary;
    }

    /** Look up all history rows for a batchId, ordered by batchRowIndex. */
    public List<ExecutionHistory> findByBatch(String batchId) {
        return historyMapper.selectByBatchId(batchId);
    }

    /** Map of ExecutionHistory to a UI-friendly summary line. */
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