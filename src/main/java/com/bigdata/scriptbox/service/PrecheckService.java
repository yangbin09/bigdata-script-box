package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.precheck.PrecheckStrategy;
import com.bigdata.scriptbox.service.precheck.PrecheckStrategyRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 执行前环境检查。
 *
 * <p>读取 {@code Script.precheckConfigJson}（JSON 格式），针对每类检查委托给
 * 实现了 {@link PrecheckStrategy} 的 Spring Bean。Service 自身不写任何检查逻辑，
 * 只负责解析配置、遍历策略、汇总结果。
 *
 * <p>支持的 JSON 字段（由各 Strategy 通过 {@link PrecheckStrategy#configKey()} 声明）：
 * <pre>{@code
 * {
 *   "kerberos": true,
 *   "commands": ["spark-sql", "hdfs"],
 *   "files": ["/opt/client/bigdata_env"],
 *   "writableDirectories": ["/tmp/bigdata-script-box"]
 * }
 * }</pre>
 *
 * <p>未知 / 缺失字段静默忽略；空配置等同"无 PreCheck"。
 *
 * <p>如何新增检查类型：实现 {@link PrecheckStrategy} 接口即可，本类无需改动。
 *
 * <p>返回类型是 {@link PrecheckReport} 而不是 {@code Map<String,Object>}：调用方
 * （{@code ScriptExecutor} 与 {@code PrecheckController}）此前必须写
 * {@code Boolean.TRUE.equals(map.get("ok"))} 这种字符串键取值，拼错编译期无感知。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PrecheckService {

    private final PrecheckStrategyRegistry registry;
    private final ObjectMapper mapper;

    /**
     * 跑一次 PreCheck，按策略分组返回详细结果与汇总状态。
     *
     * @param script 脚本实体（读 precheckConfigJson）
     * @param tenant 租户实体（部分检查会用到）
     * @return 结果；未配置时 {@link PrecheckReport#skipped()} 为 true
     */
    public PrecheckReport run(Script script, Tenant tenant) {
        if (script == null || script.getPrecheckConfigJson() == null
                || script.getPrecheckConfigJson().isBlank()) {
            return new PrecheckReport(true, true, "no precheck configured", List.of());
        }

        // 顶层 JSON 解析失败：直接判失败，避免后续策略拿到脏数据
        Map<String, Object> cfg;
        try {
            cfg = mapper.readValue(script.getPrecheckConfigJson(),
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception ex) {
            return new PrecheckReport(false, false,
                    "invalid precheck config: " + ex.getMessage(), List.of());
        }

        List<CheckResult> results = new ArrayList<>();
        boolean allOk = true;

        // 只跑配置里显式声明的 key（空配置 = 不跑任何）
        for (String key : registry.knownKeys()) {
            if (!cfg.containsKey(key)) continue;
            for (PrecheckStrategy strategy : registry.byKey(key)) {
                allOk &= runStrategy(key, strategy, script, tenant, results);
            }
        }

        return new PrecheckReport(allOk, false,
                allOk ? "all checks passed" : "one or more checks failed", results);
    }

    /**
     * 跑单个策略的全部项目。
     *
     * @return 该策略所有项目是否都通过（策略取项目列表失败时视为通过，与历史行为一致）
     */
    private boolean runStrategy(String key, PrecheckStrategy strategy,
                                Script script, Tenant tenant, List<CheckResult> results) {
        List<String> items;
        try {
            items = strategy.items(script, tenant);
        } catch (Exception ex) {
            log.warn("precheck: 策略 {} 取项目列表失败: {}", key, ex.getMessage());
            return true;
        }

        boolean allOk = true;
        for (String item : items) {
            PrecheckStrategy.CheckOutcome outcome;
            try {
                outcome = strategy.check(item, script, tenant);
            } catch (Exception ex) {
                // 策略抛异常 → 视为单条失败，不影响其他检查
                log.warn("precheck: 策略 {} 检查 {} 失败: {}", key, item, ex.getMessage());
                results.add(new CheckResult(strategy.displayName(item), false,
                        "策略异常: " + ex.getMessage()));
                allOk = false;
                continue;
            }
            results.add(new CheckResult(strategy.displayName(item), outcome.ok(), outcome.message()));
            if (!outcome.ok()) allOk = false;
        }
        return allOk;
    }

    /**
     * 单条检查结果（与前端 CheckResult 对齐）。
     *
     * @param name    展示名，例如 {@code "Kerberos"} / {@code "commands:spark-sql"}
     * @param ok      是否通过
     * @param message 说明
     */
    public record CheckResult(String name, boolean ok, String message) { }

    /**
     * 一次 PreCheck 的汇总结果。
     *
     * @param ok      是否全部通过
     * @param skipped 是否因为"没有配置"而跳过
     * @param message 汇总说明
     * @param results 每条检查的明细
     */
    public record PrecheckReport(boolean ok, boolean skipped, String message,
                                 List<CheckResult> results) {
        public PrecheckReport {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }
}
