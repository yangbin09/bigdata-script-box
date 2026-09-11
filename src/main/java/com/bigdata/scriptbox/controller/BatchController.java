package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.service.BatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/batches")
public class BatchController {

    @Autowired private BatchService batchService;

    @PostMapping("/execute")
    public ApiResponse<BatchService.BatchSummary> execute(
            @RequestBody BatchRequest req) {
        try {
            if (req.parallel && req.concurrency != null && req.concurrency > 1) {
                return ApiResponse.ok(batchService.runParallel(
                        req.scriptId, req.tenantId, req.presetId, req.rows,
                        req.concurrency, req.confirmToken));
            }
            return ApiResponse.ok(batchService.runSequential(
                    req.scriptId, req.tenantId, req.presetId, req.rows, req.confirmToken));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        }
    }

    @GetMapping("/{batchId}")
    public ApiResponse<List<Map<String, Object>>> get(@PathVariable String batchId) {
        List<ExecutionHistory> rows = batchService.findByBatch(batchId);
        return ApiResponse.ok(batchService.summarize(rows));
    }

    public static class BatchRequest {
        public Long scriptId;
        public Long tenantId;
        public Long presetId;
        public List<Map<String, String>> rows;
        public boolean parallel;
        public Integer concurrency;
        /** V2: forwarded to each row's ExecutionRequest for DANGEROUS scripts. */
        public String confirmToken;
    }
}