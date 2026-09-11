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

    @GetMapping
    public ApiResponse<List<ExecutionHistory>> list(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(historyService.listRecent(limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<ExecutionHistory> get(@PathVariable Long id) {
        ExecutionHistory h = historyService.getById(id);
        if (h == null) return ApiResponse.error("history not found");
        return ApiResponse.ok(h);
    }
}