package com.bigdata.scriptbox.service.precheck;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * "从 {@code precheckConfigJson} 里读一个字符串数组"这一类策略的公共基类。
 *
 * <p>背景：{@code commands} / {@code files} / {@code writableDirectories} 三个策略的
 * {@link #items(Script, Tenant)} 曾经是**逐字重复的三份实现**，唯一差别是常量 key。
 * 新增同类检查时也要再抄一份，极易漏改（例如忘了同步 JSON 解析失败的兜底行为）。
 *
 * <p>现在子类只需要声明 {@link #configKey()} 并实现 {@link #check}。
 *
 * <p>解析约定（与历史行为一致）：
 * <ul>
 *   <li>配置为空 / 非数组 / JSON 非法 → 返回空列表（视为"无项目可检查"）；</li>
 *   <li>顶层 JSON 非法由 {@code PrecheckService.run} 统一判失败，这里不抛。</li>
 * </ul>
 */
public abstract class AbstractJsonListPrecheckStrategy implements PrecheckStrategy {

    /** 注入容器里的 ObjectMapper（由子类构造器传入）。 */
    private final ObjectMapper mapper;

    protected AbstractJsonListPrecheckStrategy(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<String> items(Script script, Tenant tenant) {
        if (script == null) return List.of();
        String json = script.getPrecheckConfigJson();
        if (json == null || json.isBlank()) return List.of();
        try {
            Map<String, Object> cfg = mapper.readValue(json, new TypeReference<Map<String, Object>>() { });
            Object raw = cfg.get(configKey());
            if (raw instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
        } catch (Exception ignored) {
            // 配置 JSON 解析失败统一由 PrecheckService 顶层处理
        }
        return List.of();
    }
}
