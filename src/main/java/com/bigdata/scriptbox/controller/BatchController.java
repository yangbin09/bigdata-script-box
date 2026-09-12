package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.service.BatchService;
import lombok.RequiredArgsConstructor;
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
}