package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 执行历史查询接口。
 *
 * <p>提供最近执行列表（带可选过滤）、单条历史详情、以及「最近执行过的脚本」
 * 摘要列表，供前端首页或仪表盘使用。
 */
@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    /**
     * 查询执行历史，支持多维过滤。
     *
     * <p>查询参数：
     * <ul>
     *   <li>{@code limit} — 行数上限（1..500，默认 100）；</li>
     *   <li>{@code scriptId} — 按脚本过滤；</li>
     *   <li>{@code tenantId} — 按租户过滤；</li>
     *   <li>{@code status} — 状态过滤（success | failed | timeout | cancelled）；</li>
     *   <li>{@code keyword} — 对脚本名 / 租户名 / stdout / stderr 做子串匹配；</li>
     *   <li>{@code from} — 起始日期（含），格式 yyyy-MM-dd；</li>
     *   <li>{@code to} — 结束日期（不含），例如 {@code to=2026-01-15} 会保留 1/14。</li>
     * </ul>
     */
    @GetMapping
    public ApiResponse<List<ExecutionHistory>> list(@RequestParam(defaultValue = "100") int limit,
                                                    @RequestParam(required = false) Long scriptId,
                                                    @RequestParam(required = false) Long tenantId,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false)
                                                    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                                                    java.time.LocalDate from,
                                                    @RequestParam(required = false)
                                                    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                                                    java.time.LocalDate to) {
        return ApiResponse.ok(historyService.listFiltered(limit, scriptId, tenantId, status, keyword, from, to));
    }

    /**
     * 根据主键查询一条历史。
     *
     * @param id 历史主键
     * @return 历史详情
     */
    @GetMapping("/{id}")
    public ApiResponse<ExecutionHistory> get(@PathVariable Long id) {
        ExecutionHistory h = historyService.getById(id);
        if (h == null) return ApiResponse.error("history not found");
        return ApiResponse.ok(h);
    }

    /**
     * 最近执行过的脚本（按最近一次执行时间倒序），每条仅返回关键字段。
     *
     * @param limit 返回条数（夹到 1..20，默认 6）
     * @return 摘要列表
     */
    @GetMapping("/recent-scripts")
    public ApiResponse<List<HistoryService.RecentScript>> recentScripts(@RequestParam(defaultValue = "6") int limit) {
        return ApiResponse.ok(historyService.recentScripts(Math.max(1, Math.min(limit, 20))));
    }
}