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
 * 脚本预设服务。
 *
 * <p>一个预设 = 名字 + 一份捕获当时的完整参数 Map JSON。保存时不与当前脚本
 * 的 ScriptParam 定义做归一化校验，所以：
 * <ul>
 *   <li>脚本删除了某个参数 → 应用预设时静默忽略；</li>
 *   <li>脚本新增了某个参数 → 缺值时回退到 {@code defaultValue}；</li>
 * </ul>
 */
@Service
public class PresetService {

    @Autowired
    private ScriptPresetMapper presetMapper;

    private final ObjectMapper mapper;

    public PresetService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 列出脚本的全部预设。
     */
    public List<ScriptPreset> listByScript(Long scriptId) {
        return presetMapper.selectByScriptId(scriptId);
    }

    /**
     * 按 (scriptId, presetId) 查询。校验所属一致性，避免把别的脚本的预设
     * 通过 URL 拼错过来访问到。
     */
    public ScriptPreset get(Long scriptId, Long presetId) {
        ScriptPreset p = presetMapper.selectById(presetId);
        if (p == null || !p.getScriptId().equals(scriptId)) return null;
        return p;
    }

    /**
     * 创建一个预设。params 序列化为稳定的 string → string 形态。
     */
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

    /**
     * 更新一个预设；name / description / params 任一为空表示不动。
     */
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

    /**
     * 删除一个预设。
     */
    public void delete(Long scriptId, Long presetId) {
        ScriptPreset p = get(scriptId, presetId);
        if (p == null) return;
        presetMapper.deleteById(presetId);
    }

    /**
     * 把预设的参数 JSON 解析为扁平 {@code Map<String, String>}，供执行器合并。
     * 容错：解析失败返回空 Map（不会因此中断执行）。
     */
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

    /** 摘要：执行抽屉下拉用的 id/name/description 列表。 */
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

    /** 删除某脚本的全部预设（脚本删除时由 {@link ScriptService#delete} 触发）。 */
    public void deleteAllForScript(Long scriptId) {
        presetMapper.delete(new QueryWrapper<ScriptPreset>().eq("script_id", scriptId));
    }

    private String serialize(Map<String, ?> params) {
        try {
            // 始终序列化为 string → string，磁盘形态稳定，便于 diff / 日志
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