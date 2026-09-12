package com.bigdata.scriptbox.service.precheck;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PreCheck 策略注册表。
 *
 * <p>Spring 启动时自动收集所有 {@link PrecheckStrategy} Bean，按
 * {@link PrecheckStrategy#configKey()} 分组到 {@code Map<String, List<PrecheckStrategy>>}。
 *
 * <p>为何用 {@code Map<key, List<strategy>>} 而不是 {@code Map<key, strategy>}：
 * 未来可能出现多个策略共用一个 key（例如不同的 "command" 解析方式）；
 * 现阶段大部分 key 只会有一个 strategy，但 List 让扩展性无成本。
 */
@Component
public class PrecheckStrategyRegistry {

    private final Map<String, List<PrecheckStrategy>> byKey;

    /** 构造器注入：把全部 PrecheckStrategy 按 configKey() 分组。 */
    public PrecheckStrategyRegistry(List<PrecheckStrategy> strategies) {
        Map<String, List<PrecheckStrategy>> map = new HashMap<>();
        for (PrecheckStrategy s : strategies) {
            map.computeIfAbsent(s.configKey(), k -> new java.util.ArrayList<>()).add(s);
        }
        this.byKey = Map.copyOf(map);
    }

    /** 已注册的配置 key 集合。 */
    public java.util.Set<String> knownKeys() {
        return byKey.keySet();
    }

    /** 按 key 查找策略列表；未注册时返回空列表。 */
    public List<PrecheckStrategy> byKey(String key) {
        return byKey.getOrDefault(key, List.of());
    }
}
