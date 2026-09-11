package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.service.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    @Autowired private HistoryService historyService;

    /**
     * Recent executions with optional filters.
     *
     * Query params:
     *   limit        — cap on rows (1..500, default 100)
     *   scriptId     — filter by script
     *   tenantId     — filter by tenant
     *   status       — success | failed | timeout (maps to the unified labels)
     *   keyword      — substring match against scriptName / tenantName / stdout / stderr
     */
    @GetMapping
    public ApiResponse<List<ExecutionHistory>> list(@RequestParam(defaultValue = "100") int limit,
                                                    @RequestParam(required = false) Long scriptId,
                                                    @RequestParam(required = false) Long tenantId,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(historyService.listFiltered(limit, scriptId, tenantId, status, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<ExecutionHistory> get(@PathVariable Long id) {
        ExecutionHistory h = historyService.getById(id);
        if (h == null) return ApiResponse.error("history not found");
        return ApiResponse.ok(h);
    }

    /**
     * Distinct scripts recently executed, ordered by latest execution.
     * Each entry is the minimal Script fields + last execution timestamp.
     */
    @GetMapping("/recent-scripts")
    public ApiResponse<List<HistoryService.RecentScript>> recentScripts(@RequestParam(defaultValue = "6") int limit) {
        return ApiResponse.ok(historyService.recentScripts(Math.max(1, Math.min(limit, 20))));
    }
}