package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.precheck.PrecheckStrategy;
import com.bigdata.scriptbox.service.precheck.PrecheckStrategyRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行前环境检查。
 *
 * <p>读取 {@code Script.precheckConfigJson}（JSON 格式），针对每类检查委托给
 * 实现了 {@link PrecheckStrategy} 的 Spring Bean。Service 自身不写任何
 * 检查逻辑，只负责解析配置、遍历策略、汇总结果。
 *
 * <p>支持的 JSON 字段（由各 Strategy 自行声明 type）：
 * <pre>{@code
 * {
 *   "kerberos": true,
 *   "commands": ["spark-sql", "hdfs"],
 *   "files": ["/opt/client/bigdata_env"],
 *   "writableDirectories": ["/tmp/bigdata-script-box"]
 * }
 * }</pre>
 *
 * <p>未知 / 缺失字段静默忽略；空配置等同「无 PreCheck」。
 *
 * <p>如何新增检查类型：实现 {@link PrecheckStrategy} 接口即可，
 * 本类无需改动。
 */
@Service
public class PrecheckService {

    private static final Logger log = LoggerFactory.getLogger(PrecheckService.class);

    private final PrecheckStrategyRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public PrecheckService(PrecheckStrategyRegistry registry) {
        this.registry = registry;
    }

    public static class CheckResult {
        public String name;       // 显示名，例如 "Kerberos" / "command:spark-sql" / "file:/opt/client/bigdata_env"
        public boolean ok;
        public String message;
        public CheckResult() {}
        public CheckResult(String name, boolean ok, String message) {
            this.name = name; this.ok = ok; this.message = message;
        }
    }

    public Map<String, Object> run(Script script, Tenant tenant) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        summary.put("results", results);
        boolean allOk = true;

        if (script.getPrecheckConfigJson() == null || script.getPrecheckConfigJson().isBlank()) {
            summary.put("ok", true);
            summary.put("skipped", true);
            summary.put("message", "no precheck configured");
            return summary;
        }

        // 解析顶层 JSON；只要解析失败整体判失败
        Map<String, Object> cfg;
        try {
            cfg = mapper.readValue(script.getPrecheckConfigJson(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            summary.put("ok", false);
            summary.put("skipped", false);
            summary.put("message", "invalid precheck config: " + ex.getMessage());
            return summary;
        }

        for (String type : registry.knownTypes()) {
            // 仅在配置里显式出现的 type 才执行
            if (!cfg.containsKey(type)) continue;
            for (PrecheckStrategy strategy : registry.byType(type)) {
                List<String> items;
                try {
                    items = strategy.items(script, tenant);
                } catch (Exception ex) {
                    log.warn("precheck: 策略 {} 取项目列表失败: {}", type, ex.getMessage());
                    continue;
                }
                for (String item : items) {
                    PrecheckStrategy.CheckOutcome outcome;
                    try {
                        outcome = strategy.check(item, script, tenant);
                    } catch (Exception ex) {
                        // 策略抛异常 → 视为单次失败，不影响其他检查
                        log.warn("precheck: 策略 {} 检查 {} 失败: {}", type, item, ex.getMessage());
                        results.add(toMap(type + ":" + item, false, "策略异常: " + ex.getMessage()));
                        allOk = false;
                        continue;
                    }
                    String displayName = (type.equals("kerberos"))
                            ? "Kerberos"
                            : (type + ":" + (outcome.item() == null ? "" : outcome.item()));
                    results.add(toMap(displayName, outcome.ok(), outcome.message()));
                    if (!outcome.ok()) allOk = false;
                }
            }
        }

        summary.put("ok", allOk);
        summary.put("skipped", false);
        summary.put("message", allOk ? "all checks passed" : "one or more checks failed");
        return summary;
    }

    private static Map<String, Object> toMap(String name, boolean ok, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("ok", ok);
        m.put("message", message);
        return m;
    }
}
