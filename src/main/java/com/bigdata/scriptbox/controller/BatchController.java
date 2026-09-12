package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.service.BatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 批量执行控制器。
 *
 * <p>提供「同一脚本 × 多行参数」的批量执行能力。所有执行通过 {@link BatchService}
 * 委托给 {@link com.bigdata.scriptbox.executor.ScriptExecutor}，每行参数生成一条
 * ExecutionHistory，并共享同一个 {@code batchId} 便于追踪。
 */
@RestController
@RequestMapping("/api/batches")
@RequiredArgsConstructor
@Slf4j
public class BatchController {

    private final BatchService batchService;

    /**
     * 触发批量执行。
     *
     * <p>根据请求体的 {@code parallel} 与 {@code concurrency} 决定串行还是并行执行。
     * DANGEROUS 脚本必须传入 {@code confirmToken} 才会被允许运行。
     *
     * @param req 批量执行请求体
     * @return 批量执行汇总
     */
    @PostMapping("/execute")
    public ApiResponse<BatchService.BatchSummary> execute(
            @RequestBody BatchRequest req) {
        try {
            BatchService.BatchSummary summary;
            if (req.parallel && req.concurrency != null && req.concurrency > 1) {
                summary = batchService.runParallel(
                        req.scriptId, req.tenantId, req.presetId, req.rows,
                        req.concurrency, req.confirmToken);
            } else {
                summary = batchService.runSequential(
                        req.scriptId, req.tenantId, req.presetId, req.rows, req.confirmToken);
            }
            return ApiResponse.ok(summary);
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        }
    }

    /**
     * 根据 batchId 查询所有相关执行记录，并返回前端友好的汇总列表。
     *
     * @param batchId 批量执行 ID
     * @return 汇总条目列表
     */
    @GetMapping("/{batchId}")
    public ApiResponse<List<Map<String, Object>>> get(@PathVariable String batchId) {
        List<ExecutionHistory> rows = batchService.findByBatch(batchId);
        return ApiResponse.ok(batchService.summarize(rows));
    }

    /**
     * 批量执行请求体。
     */
    public static class BatchRequest {
        /** 脚本 ID。 */
        public Long scriptId;
        /** 租户 ID。 */
        public Long tenantId;
        /** 预设 ID（可空）。 */
        public Long presetId;
        /** 多行参数；每行对应一次独立执行。 */
        public List<Map<String, String>> rows;
        /** 是否启用并行执行。 */
        public boolean parallel;
        /** 并行度（仅在 {@code parallel=true} 时生效，>1 才会真正并行）。 */
        public Integer concurrency;
        /** V2: 透传到每行 ExecutionRequest，用于 DANGEROUS 脚本的二次确认。 */
        public String confirmToken;
    }

    /**
     * V3 (PR-8): 重跑 batch 中的某一行（行号 0..N-1）。
     *
     * <p>前端拿到 batch summary + 失败行号列表后，对每行调用此接口重跑；新行写
     * {@code parent_execution_id = 原历史 ID}。若原行已是 SUCCESS 默认拒绝；
     * body 里 {@code force=true} 可强制重跑。
     */
    @PostMapping("/{batchId}/retry/{rowIndex}")
    public ApiResponse<ExecutionHistory> retryRow(
            @PathVariable String batchId,
            @PathVariable int rowIndex,
            @RequestParam(defaultValue = "false") boolean force) {
        try {
            ExecutionHistory h = batchService.retryRow(batchId, rowIndex, force);
            return ApiResponse.ok(h);
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        } catch (IllegalStateException ex) {
            return ApiResponse.error(ex.getMessage());
        } catch (java.io.IOException ex) {
            log.warn("batch retry I/O 失败 batch={} row={}: {}", batchId, rowIndex, ex.getMessage());
            return ApiResponse.error("IO 失败：" + ex.getMessage());
        }
    }
}