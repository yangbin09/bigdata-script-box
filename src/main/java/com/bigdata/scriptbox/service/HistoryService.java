package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class HistoryService {

    @Autowired
    private ExecutionHistoryMapper historyMapper;

    public List<ExecutionHistory> listRecent(int limit) {
        return historyMapper.selectList(
                new QueryWrapper<ExecutionHistory>()
                        .orderByDesc("id")
                        .last("LIMIT " + Math.max(1, Math.min(limit, 500))));
    }

    /**
     * Filtered query: by scriptId, tenantId, status (success/failed/timeout), keyword (substring
     * against script/tenant name), all combined with AND. Status is the canonical short name
     * used by the unified status vocabulary.
     */
    public List<ExecutionHistory> listFiltered(int limit, Long scriptId, Long tenantId,
                                                String status, String keyword) {
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
                // V2: CANCELLED is the canonical status string written by the
                // executor when the cancel endpoint flips the registry flag.
                case "cancelled" -> q.eq("status", "CANCELLED");
                default -> { /* ignore unknown status */ }
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            q.and(w -> w.like("script_name", like).or().like("tenant_name", like));
        }
        return historyMapper.selectList(q);
    }

    public ExecutionHistory getById(Long id) {
        return historyMapper.selectById(id);
    }

    /**
     * Distinct scripts recently executed. Done in Java rather than SQL because H2 doesn't have
     * window functions in the version we ship with; the per-script scan is bounded by LIMIT 6.
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
            r.lastTenantName = h.getTenantName();
            map.put(h.getScriptId(), r);
            if (map.size() >= limit) break;
        }
        return new ArrayList<>(map.values());
    }

    public static class RecentScript {
        public Long id;
        public String scriptName;
        public String lastTenantName;
        public Boolean lastSuccess;
        public LocalDateTime lastStartTime;
    }
}