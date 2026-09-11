package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.GlobalVariable;
import com.bigdata.scriptbox.mapper.GlobalVariableMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 全局变量服务。
 *
 * <p>每次脚本执行前把本表中"启用"的项目注入到
 * {@link ProcessBuilder#environment()}。变量 key 必须匹配
 * {@code [A-Za-z_][A-Za-z0-9_]*} 以保证 shell 环境契约；敏感值从不写到日志
 * 与 API 响应里，列表接口自动遮罩。
 */
@Service
public class GlobalVariableService {

    private static final Logger log = LoggerFactory.getLogger(GlobalVariableService.class);

    /** 环境变量名合法字符集。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final GlobalVariableMapper variableMapper;

    /** 构造器注入：依赖显式化，字段 final 不可变。 */
    public GlobalVariableService(GlobalVariableMapper variableMapper) {
        this.variableMapper = variableMapper;
    }

    /**
     * 列出全部变量（含禁用项）。按 ID 升序。
     */
    public List<GlobalVariable> listAll() {
        return variableMapper.selectList(new QueryWrapper<GlobalVariable>().orderByAsc("id"));
    }

    /**
     * 列出启用的变量（执行环境注入用）。
     */
    public List<GlobalVariable> listEnabled() {
        return variableMapper.selectEnabled();
    }

    /**
     * 列表接口的脱敏视图：敏感值显示为 {@code ******}，非敏感项原样返回。
     */
    public List<Map<String, Object>> listSummary() {
        return listAll().stream().map(v -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", v.getId());
            out.put("variableKey", v.getVariableKey());
            out.put("description", v.getDescription());
            out.put("sensitive", Boolean.TRUE.equals(v.getSensitive()));
            out.put("enabled", v.getEnabled() == null ? Boolean.TRUE : v.getEnabled());
            // 敏感变量值永远不回明文；用占位符 + hasValue 标记是否真的设了
            if (Boolean.TRUE.equals(v.getSensitive())) {
                out.put("variableValue", "******");
                out.put("variableValueSet", v.getVariableValue() != null && !v.getVariableValue().isEmpty());
            } else {
                out.put("variableValue", v.getVariableValue() == null ? "" : v.getVariableValue());
                out.put("variableValueSet", v.getVariableValue() != null && !v.getVariableValue().isEmpty());
            }
            return out;
        }).toList();
    }

    /**
     * 按 ID 查询单个变量（返回明文，调用方负责遮罩）。
     */
    public GlobalVariable get(Long id) {
        return variableMapper.selectById(id);
    }

    /**
     * 创建一个全局变量。重复 key 拒绝。
     */
    public GlobalVariable create(String key, String value, String description, boolean sensitive, boolean enabled) {
        validateKey(key);
        if (variableMapper.selectCount(new QueryWrapper<GlobalVariable>().eq("variable_key", key)) > 0)
            throw new IllegalArgumentException("variable key already exists: " + key);
        GlobalVariable v = new GlobalVariable();
        v.setVariableKey(key);
        v.setVariableValue(value == null ? "" : value);
        v.setDescription(description == null ? "" : description);
        v.setSensitive(sensitive);
        v.setEnabled(enabled);
        LocalDateTime now = LocalDateTime.now();
        v.setCreateTime(now);
        v.setUpdateTime(now);
        variableMapper.insert(v);
        log.info("新增全局变量，variableKey={}，sensitive={}", key, sensitive);
        return v;
    }

    /**
     * 更新一个全局变量。key 为空表示不动。
     */
    public GlobalVariable update(Long id, String key, String value, String description,
                                 boolean sensitive, boolean enabled) {
        GlobalVariable v = variableMapper.selectById(id);
        if (v == null) throw new IllegalArgumentException("variable not found");
        if (key != null && !key.isBlank()) {
            validateKey(key);
            if (!key.equals(v.getVariableKey())) {
                if (variableMapper.selectCount(
                        new QueryWrapper<GlobalVariable>().eq("variable_key", key).ne("id", id)) > 0)
                    throw new IllegalArgumentException("variable key already exists: " + key);
                v.setVariableKey(key);
            }
        }
        if (value != null) v.setVariableValue(value);
        if (description != null) v.setDescription(description);
        v.setSensitive(sensitive);
        v.setEnabled(enabled);
        v.setUpdateTime(LocalDateTime.now());
        variableMapper.updateById(v);
        return v;
    }

    /**
     * 删除一个全局变量。
     */
    public void delete(Long id) {
        variableMapper.deleteById(id);
        log.info("删除全局变量，variableId={}", id);
    }

    /**
     * 收集全部启用项到 {@code Map<String, String>}，注入到 ProcessBuilder 环境。
     * 返回的是新 Map，调用方可自由覆盖（mock 测试场景）。
     */
    public Map<String, String> envForExecution() {
        Map<String, String> env = new LinkedHashMap<>();
        for (GlobalVariable v : listEnabled()) {
            if (v.getVariableKey() == null || v.getVariableKey().isBlank()) continue;
            env.put(v.getVariableKey(), v.getVariableValue() == null ? "" : v.getVariableValue());
        }
        return env;
    }

    /** 单条遮罩（仅用于预览 / 详情接口），返回脱敏后的字符串。 */
    public String maskedValue(GlobalVariable v) {
        if (v == null) return "";
        if (Boolean.TRUE.equals(v.getSensitive())) {
            return v.getVariableValue() != null && !v.getVariableValue().isEmpty() ? "******" : "";
        }
        return v.getVariableValue() == null ? "" : v.getVariableValue();
    }

    /**
     * 校验变量名：必填、长度限制、字符集匹配。
     */
    private void validateKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("variable key is required");
        if (!KEY_PATTERN.matcher(key).matches())
            throw new IllegalArgumentException(
                "invalid variable key: must match [A-Za-z_][A-Za-z0-9_]*");
        if (key.length() > 128) throw new IllegalArgumentException("variable key too long");
    }
}