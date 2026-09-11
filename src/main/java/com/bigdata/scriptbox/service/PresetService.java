package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.ScriptPreset;
import com.bigdata.scriptbox.mapper.ScriptPresetMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD + apply for script parameter presets. A preset is just a name + the
 * full param map JSON captured at the moment of save. We do not normalize
 * against the current script schema at save time, so removed/renamed params
 * are silently ignored on apply, and added params fall back to their default.
 */
@Service
public class PresetService {

    @Autowired
    private ScriptPresetMapper presetMapper;

    private final ObjectMapper mapper;

    public PresetService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<ScriptPreset> listByScript(Long scriptId) {
        return presetMapper.selectByScriptId(scriptId);
    }

    public ScriptPreset get(Long scriptId, Long presetId) {
        ScriptPreset p = presetMapper.selectById(presetId);
        if (p == null || !p.getScriptId().equals(scriptId)) return null;
        return p;
    }

    public ScriptPreset create(Long scriptId, String name, String description, Map<String, ?> params) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("preset name is required");
        ScriptPreset p = new ScriptPreset();
        p.setScriptId(scriptId);
        p.setName(name.trim());
        p.setDescription(description == null ? "" : description.trim());
        p.setParamsJson(serialize(params));
        LocalDateTime now = LocalDateTime.now();
        p.setCreateTime(now);
        p.setUpdateTime(now);
        presetMapper.insert(p);
        return p;
    }

    public ScriptPreset update(Long scriptId, Long presetId, String name, String description, Map<String, ?> params) {
        ScriptPreset p = get(scriptId, presetId);
        if (p == null) throw new IllegalArgumentException("preset not found");
        if (name != null && !name.isBlank()) p.setName(name.trim());
        if (description != null) p.setDescription(description.trim());
        if (params != null) p.setParamsJson(serialize(params));
        p.setUpdateTime(LocalDateTime.now());
        presetMapper.updateById(p);
        return p;
    }

    public void delete(Long scriptId, Long presetId) {
        ScriptPreset p = get(scriptId, presetId);
        if (p == null) return;
        presetMapper.deleteById(presetId);
    }

    /** Return the params as a flat string map, regardless of how they were captured. */
    public Map<String, String> applyParams(ScriptPreset p) {
        if (p == null || p.getParamsJson() == null || p.getParamsJson().isBlank()) return Map.of();
        try {
            Map<String, Object> raw = mapper.readValue(p.getParamsJson(), new TypeReference<Map<String, Object>>() {});
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) continue;
                Object v = e.getValue();
                if (v == null) continue;
                out.put(e.getKey(), String.valueOf(v));
            }
            return out;
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    /** All presets for a script as a {id, name} list (used by the execute drawer dropdown). */
    public List<Map<String, Object>> listSummary(Long scriptId) {
        List<ScriptPreset> list = listByScript(scriptId);
        return list.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("description", p.getDescription());
            return m;
        }).toList();
    }

    public void deleteAllForScript(Long scriptId) {
        presetMapper.delete(new QueryWrapper<ScriptPreset>().eq("script_id", scriptId));
    }

    private String serialize(Map<String, ?> params) {
        try {
            // Always serialize as string→string for stable on-disk shape.
            Map<String, String> flat = new LinkedHashMap<>();
            if (params != null) {
                for (Map.Entry<String, ?> e : params.entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank()) continue;
                    Object v = e.getValue();
                    if (v == null) continue;
                    flat.put(e.getKey(), String.valueOf(v));
                }
            }
            return mapper.writeValueAsString(flat);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid params: " + ex.getMessage());
        }
    }
}