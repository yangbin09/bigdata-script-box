package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.QuickAction;
import com.bigdata.scriptbox.service.QuickActionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * V3 (PR-5): 快捷操作 HTTP 接口。
 *
 * <p>{@code POST /api/quick-actions/reorder} body 形如 {@code [3,1,2]}，
 * 按列表顺序重写 sort_order。
 */
@RestController
@RequestMapping("/api/quick-actions")
@Slf4j
@RequiredArgsConstructor
public class QuickActionController {

    private final QuickActionService quickActionService;

    @GetMapping
    public ApiResponse<List<QuickAction>> list() {
        return ApiResponse.ok(quickActionService.listAll());
    }

    @PostMapping
    public ApiResponse<QuickAction> create(@RequestBody QuickAction qa) {
        return ApiResponse.ok(quickActionService.create(qa));
    }

    @PutMapping("/{id}")
    public ApiResponse<QuickAction> update(@PathVariable Long id, @RequestBody QuickAction qa) {
        qa.setId(id);
        return ApiResponse.ok(quickActionService.update(qa));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        quickActionService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/reorder")
    public ApiResponse<Void> reorder(@RequestBody List<Long> orderedIds) {
        quickActionService.reorder(orderedIds);
        return ApiResponse.ok();
    }
}