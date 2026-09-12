package com.bigdata.scriptbox.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「这个脚本上次成功时是怎么跑的」——从执行历史推导出来的可用方案。
 *
 * <p><b>为什么需要它</b>：原先用户要复用一套参数，必须先攒好参数 → 起名 →
 * 存成参数方案（Preset）→ 执行时再选一次。但 {@code execution_history} 里
 * 本来就存着每次执行的 {@code parameters_json} 与 {@code success} ——
 * 「上次成功用的参数」是一个**既有事实**，不需要用户创建、命名、保存。
 *
 * <p>因此：<b>任何一次成功执行的参数，天然就是一个合法方案。</b>
 * 本 DTO 就是把这个事实读出来，供「重跑上次」与表单默认值使用。
 *
 * <p>{@code parameters} 为有序 Map，保持历史行里的键序，便于前端直接灌进表单。
 */
@Data
public class ScriptPlan {

    /** 脚本 ID。 */
    private Long scriptId;

    /** 推导来源的执行 ID（前端可用于「查看那次执行」）。 */
    private Long executionId;

    /** 那次执行用的租户 ID。 */
    private Long tenantId;

    /** 那次执行用的租户名（历史快照，租户改名后仍显示当时的名字）。 */
    private String tenantName;

    /** 那次执行的结束时间，用于展示「2 小时前」。 */
    private java.time.LocalDateTime executedAt;

    /** 那次执行的耗时（毫秒），供前端提示「上次 2.3s」。 */
    private Long durationMs;

    /** 参数键值对（有序，直接灌进表单）。 */
    private Map<String, String> parameters = new LinkedHashMap<>();

    /** 没有任何成功历史时的空方案（而不是 null，省掉前端判空）。 */
    public static ScriptPlan empty(Long scriptId) {
        ScriptPlan p = new ScriptPlan();
        p.setScriptId(scriptId);
        p.setParameters(new LinkedHashMap<>());
        return p;
    }

    // ==================================================================
    // 历史聚合（「按脚本 + 按天」概览，取代 200 条流水列表）
    // ==================================================================

    /**
     * 某一天某个脚本的执行聚合。
     *
     * <p>运维真正要回答的问题是「昨天哪个脚本失败了」，而不是「第 137 行是什么」。
     */
    @Data
    public static class DayStat {
        /** yyyy-MM-dd */
        private String day;
        private int total;
        private int succeeded;
        private int failed;
        private int timedOut;
        private int cancelled;
        /** 当天最慢一次的耗时（毫秒），便于发现劣化。 */
        private Long maxDurationMs;
    }

    /** 一个脚本在回看窗口内的概览。 */
    @Data
    public static class ScriptDigest {
        private Long scriptId;
        private String scriptName;
        private String displayName;
        private String category;
        private String lastStatus;
        private java.time.LocalDateTime lastRunAt;
        /** 回看窗口内的总次数。 */
        private int total;
        private int succeeded;
        private int failed;
        /** 最近一次成功执行的 ID（可直接用于「重跑上次」）。 */
        private Long lastSuccessExecutionId;
        /** 按天聚合，倒序（最近的一天在前）。 */
        private java.util.List<DayStat> days = new java.util.ArrayList<>();

        /** 失败率，0~1；0 次执行时为 0。 */
        public double getFailureRate() {
            return total == 0 ? 0d : (double) failed / total;
        }
    }
}
