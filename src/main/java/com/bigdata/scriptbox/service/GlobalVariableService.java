package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.GlobalVariable;
import com.bigdata.scriptbox.mapper.GlobalVariableMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Site-wide variables injected into ProcessBuilder.environment() before each
 * script execution. Variable keys must be [A-Za-z_][A-Za-z0-9_]* to avoid
 * breaking the shell environment contract. Sensitive values are never
 * logged or surfaced in API results — they are masked at the list endpoint.
 */
@Service
public class GlobalVariableService {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    @Autowired
    private GlobalVariableMapper variableMapper;

    public List<GlobalVariable> listAll() {
        return variableMapper.selectList(new QueryWrapper<GlobalVariable>().orderByAsc("id"));
    }

    public List<GlobalVariable> listEnabled() {
        return variableMapper.selectEnabled();
    }

    /**
     * Return a sanitized view for the UI: enabled list with sensitive values masked.
     */
    public List<Map<String, Object>> listSummary() {
        return listAll().stream().map(v -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", v.getId());
            out.put("variableKey", v.getVariableKey());
            out.put("description", v.getDescription());
            out.put("sensitive", Boolean.TRUE.equals(v.getSensitive()));
            out.put("enabled", v.getEnabled() == null ? Boolean.TRUE : v.getEnabled());
            // Mask sensitive values; for non-sensitive, surface as-is so user can read.
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

    public GlobalVariable get(Long id) {
        return variableMapper.selectById(id);
    }

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
        return v;
    }

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

    public void delete(Long id) {
        variableMapper.deleteById(id);
    }

    /**
     * Inject all enabled variables into the ProcessBuilder's environment map.
     * Returned as a new mutable LinkedHashMap so callers can layer their own
     * overrides (e.g. test mocks). Does NOT include disabled or missing keys.
     */
    public Map<String, String> envForExecution() {
        Map<String, String> env = new LinkedHashMap<>();
        for (GlobalVariable v : listEnabled()) {
            if (v.getVariableKey() == null || v.getVariableKey().isBlank()) continue;
            env.put(v.getVariableKey(), v.getVariableValue() == null ? "" : v.getVariableValue());
        }
        return env;
    }

    /** Mask value if sensitive. Used in dry-run and preview. */
    public String maskedValue(GlobalVariable v) {
        if (v == null) return "";
        if (Boolean.TRUE.equals(v.getSensitive())) {
            return v.getVariableValue() != null && !v.getVariableValue().isEmpty() ? "******" : "";
        }
        return v.getVariableValue() == null ? "" : v.getVariableValue();
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("variable key is required");
        if (!KEY_PATTERN.matcher(key).matches())
            throw new IllegalArgumentException(
                "invalid variable key: must match [A-Za-z_][A-Za-z0-9_]*");
        if (key.length() > 128) throw new IllegalArgumentException("variable key too long");
    }
}