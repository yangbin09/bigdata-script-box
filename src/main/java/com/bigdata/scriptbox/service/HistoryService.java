package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.dto.ScriptPlan;
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
    private final com.bigdata.scriptbox.mapper.ScriptMapper scriptMapper;

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
     * 推导「这个脚本上次成功时是怎么跑的」。
     *
     * <p>这是「参数方案不必手动创建」的实现基础：成功执行的 {@code parameters_json}
     * 本身就是一套可用方案，取最近一条即可，不需要用户起名保存。
     *
     * @param scriptId 脚本 ID
     * @param tenantId 可选；指定时只在该租户的历史里找（不同租户参数往往不同）
     * @return 方案；没有任何成功历史时返回 {@code ScriptPlan.empty(scriptId)}（非 null）
     */
    public ScriptPlan lastSuccessfulPlan(Long scriptId, Long tenantId) {
        ScriptPlan plan = ScriptPlan.empty(scriptId);
        if (scriptId == null) return plan;

        QueryWrapper<ExecutionHistory> q = new QueryWrapper<ExecutionHistory>()
                .eq("script_id", scriptId)
                // 只要成功：status 是 V1.5 引入的规范串，success 兼容更早的行
                .and(w -> w.eq("status", "SUCCESS").or().eq("success", true))
                .orderByDesc("id")
                .last("LIMIT 1");
        if (tenantId != null) q.eq("tenant_id", tenantId);

        ExecutionHistory h;
        try {
            h = historyMapper.selectOne(q);
        } catch (Exception ex) {
            // 历史表不可读不应让「打开执行抽屉」失败
            return plan;
        }
        if (h == null) return plan;

        plan.setExecutionId(h.getId());
        plan.setTenantId(h.getTenantId());
        plan.setTenantName(h.getTenantName());
        plan.setExecutedAt(h.getEndTime() != null ? h.getEndTime() : h.getStartTime());
        plan.setDurationMs(h.getDurationMs());
        plan.setParameters(parseParams(h.getParametersJson()));
        return plan;
    }

    /**
     * 解析历史行的参数 JSON。坏数据（脏 JSON / 非对象）返回空 Map —— 方案推导
     * 是「锦上添花」，不能因为一条脏历史就让执行入口报错。
     */
    static java.util.Map<String, String> parseParams(String json) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (node == null || !node.isObject()) return out;
            node.fields().forEachRemaining(e -> {
                com.fasterxml.jackson.databind.JsonNode v = e.getValue();
                out.put(e.getKey(), v == null || v.isNull() ? "" : v.asText());
            });
        } catch (Exception ignored) {
            // 脏数据按「无方案」处理
        }
        return out;
    }

    /**
     * 按「脚本 + 按天」聚合的历史概览。
     *
     * <p>取代「200 条流水 + 5 个筛选器」的 DBA 视角：运维要看的是
     * 「昨天哪个脚本失败了」「这个脚本最近稳不稳」。默认窗口 7 天。
     *
     * <p>聚合放在 Java 层（而非 SQL 的 GROUP BY DATE）的原因：H2 与真实环境的
     * 日期函数不完全一致，而窗口只有几百到几千行，代价可以忽略。
     *
     * @param days 回看天数（夹到 1..90）
     */
    public List<ScriptPlan.ScriptDigest> digest(int days) {
        int window = Math.max(1, Math.min(days, 90));
        java.time.LocalDate from = java.time.LocalDate.now().minusDays(window - 1L);

        List<ExecutionHistory> rows = historyMapper.selectList(
                new QueryWrapper<ExecutionHistory>()
                        .ge("start_time", from.atStartOfDay())
                        .orderByDesc("id")
                        .last("LIMIT 5000"));

        java.util.LinkedHashMap<Long, ScriptPlan.ScriptDigest> byScript = new java.util.LinkedHashMap<>();
        java.util.Map<Long, java.util.Map<String, ScriptPlan.DayStat>> dayIndex = new java.util.HashMap<>();

        for (ExecutionHistory h : rows) {
            if (h.getScriptId() == null) continue;
            ScriptPlan.ScriptDigest d = byScript.computeIfAbsent(h.getScriptId(), k -> {
                ScriptPlan.ScriptDigest x = new ScriptPlan.ScriptDigest();
                x.setScriptId(h.getScriptId());
                x.setScriptName(h.getScriptName());
                return x;
            });
            // rows 按 id 倒序 → 第一条即最近一次
            if (d.getLastRunAt() == null) {
                d.setLastRunAt(h.getStartTime());
                d.setLastStatus(deriveStatus(h));
            }
            d.setTotal(d.getTotal() + 1);

            String status = deriveStatus(h);
            switch (status) {
                case "success" -> {
                    d.setSucceeded(d.getSucceeded() + 1);
                    if (d.getLastSuccessExecutionId() == null) d.setLastSuccessExecutionId(h.getId());
                }
                case "cancelled" -> { /* 取消不计入失败，避免污染失败率 */ }
                default -> d.setFailed(d.getFailed() + 1);
            }

            if (h.getStartTime() != null) {
                String day = h.getStartTime().toLocalDate().toString();
                ScriptPlan.DayStat stat = dayIndex
                        .computeIfAbsent(h.getScriptId(), k -> new java.util.HashMap<>())
                        .computeIfAbsent(day, k -> {
                            ScriptPlan.DayStat s = new ScriptPlan.DayStat();
                            s.setDay(k);
                            return s;
                        });
                stat.setTotal(stat.getTotal() + 1);
                switch (status) {
                    case "success" -> stat.setSucceeded(stat.getSucceeded() + 1);
                    case "timeout" -> stat.setTimedOut(stat.getTimedOut() + 1);
                    case "cancelled" -> stat.setCancelled(stat.getCancelled() + 1);
                    default -> stat.setFailed(stat.getFailed() + 1);
                }
                if (h.getDurationMs() != null
                        && (stat.getMaxDurationMs() == null || h.getDurationMs() > stat.getMaxDurationMs())) {
                    stat.setMaxDurationMs(h.getDurationMs());
                }
            }
        }

        for (ScriptPlan.ScriptDigest d : byScript.values()) {
            java.util.Map<String, ScriptPlan.DayStat> m =
                    dayIndex.getOrDefault(d.getScriptId(), java.util.Map.of());
            java.util.List<ScriptPlan.DayStat> stats = new java.util.ArrayList<>(m.values());
            stats.sort(java.util.Comparator.comparing(ScriptPlan.DayStat::getDay).reversed());
            d.setDays(stats);
            // 历史行里的 scriptName 是执行当时的快照；展示名/分类要读当前脚本定义，
            // 否则脚本改名后概览行还是旧名字。
            enrichFromScript(d);
        }
        // 有失败的排前面，其次按累计次数 —— 让"需要看的"浮到最上面
        return byScript.values().stream()
                .sorted(java.util.Comparator
                        .comparingInt(ScriptPlan.ScriptDigest::getFailed).reversed()
                        .thenComparing(java.util.Comparator.comparingInt(ScriptPlan.ScriptDigest::getTotal).reversed()))
                .toList();
    }

    /** 用当前脚本定义补齐展示名 / 分类；脚本被删则保留历史快照里的名字。 */
    private void enrichFromScript(ScriptPlan.ScriptDigest d) {
        try {
            var s = scriptMapper.selectById(d.getScriptId());
            if (s == null) return;
            if (s.getDisplayName() != null) d.setDisplayName(s.getDisplayName());
            d.setCategory(s.getCategory());
            if (s.getName() != null) d.setScriptName(s.getName());
        } catch (Exception ignored) {
            // 读脚本失败不该让概览整页 500
        }
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