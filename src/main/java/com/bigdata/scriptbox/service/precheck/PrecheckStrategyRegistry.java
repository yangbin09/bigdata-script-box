package com.bigdata.scriptbox.service.precheck;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PreCheck 策略注册表。
 *
 * <p>Spring 启动时自动收集所有 {@link PrecheckStrategy} Bean，按 {@link PrecheckStrategy#type()}
 * 分组到 {@code Map<String, List<PrecheckStrategy>>}。
 *
 * <p>为何用 {@code Map<type, List<strategy>>} 而不是 {@code Map<type, strategy>}：
 * 未来可能出现多个策略共用一个 type（例如不同的 "command" 解析方式）；
 * 现阶段大部分 type 只会有一个 strategy，但 List 让扩展性无成本。
 *
 * <p>使用示例：
 * <pre>{@code
 * for (PrecheckStrategy s : registry.byType("kerberos")) {
 *     for (String item : s.items(script, tenant)) {
 *         CheckOutcome r = s.check(item, script, tenant);
 *     }
 * }
 * }</pre>
 */
@Component
public class PrecheckStrategyRegistry {

    private final Map<String, List<PrecheckStrategy>> byType;

    public PrecheckStrategyRegistry(List<PrecheckStrategy> strategies) {
        Map<String, List<PrecheckStrategy>> map = new HashMap<>();
        for (PrecheckStrategy s : strategies) {
            map.computeIfAbsent(s.type(), k -> new java.util.ArrayList<>()).add(s);
        }
        this.byType = Map.copyOf(map);
    }

    /** 已知 type 集合（方便日志 / 调试）。 */
    public java.util.Set<String> knownTypes() {
        return byType.keySet();
    }

    /** 按 type 查找策略列表；未注册时返回空列表。 */
    public List<PrecheckStrategy> byType(String type) {
        return byType.getOrDefault(type, List.of());
    }
}
