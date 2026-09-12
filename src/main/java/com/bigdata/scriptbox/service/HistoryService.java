package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行历史查询服务。
 *
 * <p>提供按过滤条件的历史查询、最近脚本摘要等只读能力。
 */
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final ExecutionHistoryMapper historyMapper;

    /**
     * 最近 N 条历史。
     */
    public List<ExecutionHistory> listRecent(int limit) {
        return historyMapper.selectList(
                new QueryWrapper<ExecutionHistory>()
                        .orderByDesc("id")
                        .last("LIMIT " + Math.max(1, Math.min(limit, 500))));
    }

    /**
     * 多维过滤查询：按 scriptId / tenantId / 状态 / 关键字 / 日期范围，全部 AND。
     * 状态参数是规范化短串（success / failed / timeout / cancelled）。
     */
    public List<ExecutionHistory> listFiltered(int limit, Long scriptId, Long tenantId,
                                                String status, String keyword,
                                                java.time.LocalDate from, java.time.LocalDate to) {
        QueryWrapper<ExecutionHistory> q = new QueryWrapper<ExecutionHistory>()
                .orderByDesc("id")
                .last("LIMIT " + Math.max(1, Math.min(limit, 500)));
        if (scriptId != null) q.eq("script_id", scriptId);
        if (tenantId != null) q.eq("tenant_id", tenantId);
        if (status != null && !status.isBlank()) {
            switch (status.toLowerCase()) {
                case "success"   -> q.eq("success", true).eq("timeout", false);
                case "failed"    -> q.eq("success", false).eq("timeout", false);
                case "timeout"   -> q.eq("timeout", true);
                // V2: CANCELLED 是 cancel 接口触发时写库的规范化状态串
                case "cancelled" -> q.eq("status", "CANCELLED");
                default -> { /* 未知状态忽略 */ }
            }
        }
        // from 含当天，to 不含当天：避免 [from, to] 闭区间让跨日的批次翻倍
        if (from != null) q.ge("start_time", from.atStartOfDay());
        if (to != null) q.lt("start_time", to.plusDays(1).atStartOfDay());
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            q.and(w -> w.like("script_name", like).or().like("tenant_name", like));
        }
        return historyMapper.selectList(q);
    }

    /**
     * 按 ID 查询单条历史。
     */
    public ExecutionHistory getById(Long id) {
        return historyMapper.selectById(id);
    }

    /**
     * 最近执行过的脚本（按最近一次执行时间倒序）。在 Java 层做去重是因为 H2
     * 当前版本不带窗口函数；单脚本只扫到目标 limit 条即可停。
     */
    public List<RecentScript> recentScripts(int limit) {
        List<ExecutionHistory> recent = historyMapper.selectList(
                new QueryWrapper<ExecutionHistory>()
                        .orderByDesc("id")
                        .last("LIMIT " + Math.max(50, limit * 10)));
        java.util.LinkedHashMap<Long, RecentScript> map = new java.util.LinkedHashMap<>();
        for (ExecutionHistory h : recent) {
            if (h.getScriptId() == null) continue;
            if (map.containsKey(h.getScriptId())) continue;
            RecentScript r = new RecentScript();
            r.id = h.getScriptId();
            r.scriptName = h.getScriptName();
            r.lastStartTime = h.getStartTime();
            r.lastSuccess = Boolean.TRUE.equals(h.getSuccess());
            r.lastStatus = deriveStatus(h);
            r.lastTenantName = h.getTenantName();
            map.put(h.getScriptId(), r);
            if (map.size() >= limit) break;
        }
        return new ArrayList<>(map.values());
    }

    /**
     * 把 history 行归一化成 UI 用的状态短串；与前端 statusOfHistory() 对齐。
     */
    private static String deriveStatus(ExecutionHistory h) {
        if (h == null) return "UNKNOWN";
        String status = h.getStatus();
        if ("CANCELLED".equals(status)) return "cancelled";
        if ("RUNNING".equals(status))   return "running";
        if (Boolean.TRUE.equals(h.getTimeout())) return "timeout";
        if (Boolean.TRUE.equals(h.getSuccess())) return "success";
        return "failed";
    }

    /** 「最近执行过的脚本」摘要。 */
    public static class RecentScript {
        public Long id;
        public String scriptName;
        public String lastTenantName;
        public Boolean lastSuccess;
        /** V2: 全状态（SUCCESS / FAILED / TIMEOUT / CANCELLED / RUNNING）。 */
        public String lastStatus;
        public LocalDateTime lastStartTime;
    }
}