package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ScriptPreset;
import com.bigdata.scriptbox.service.PresetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本预设管理接口。
 *
 * <p>预设（Preset）是「脚本 + 一组参数值」的快速复用入口：
 * <ul>
 *   <li>列表 / 摘要：管理页 / 执行抽屉使用；</li>
 *   <li>详情：包含参数 JSON 解析后的扁平 Map；</li>
 *   <li>apply：执行前快速把预设参数合并进 ExecutionRequest。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/scripts/{scriptId}/presets")
public class PresetController {

    @Autowired private PresetService presetService;

    /**
     * 列出脚本的全部预设。
     *
     * @param scriptId 脚本 ID
     * @return 预设列表
     */
    @GetMapping
    public ApiResponse<List<ScriptPreset>> list(@PathVariable Long scriptId) {
        return ApiResponse.ok(presetService.listByScript(scriptId));
    }

    /**
     * 列出脚本预设的摘要信息（仅 id/name/description）。
     *
     * @param scriptId 脚本 ID
     * @return 摘要列表
     */
    @GetMapping("/summary")
    public ApiResponse<List<Map<String, Object>>> summary(@PathVariable Long scriptId) {
        return ApiResponse.ok(presetService.listSummary(scriptId));
    }

    /**
     * 查询单个预设，同时返回其展开后的参数 Map。
     *
     * @param scriptId 脚本 ID
     * @param presetId 预设 ID
     * @return 预设实体 + 参数 Map
     */
    @GetMapping("/{presetId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long scriptId, @PathVariable Long presetId) {
        ScriptPreset p = presetService.get(scriptId, presetId);
        if (p == null) return ApiResponse.error("preset not found");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("preset", p);
        data.put("params", presetService.applyParams(p));
        return ApiResponse.ok(data);
    }

    /**
     * 创建一个预设。
     *
     * @param scriptId 脚本 ID
     * @param body 请求体，含 name / description / params
     * @return 新建的预设
     */
    @PostMapping
    public ApiResponse<ScriptPreset> create(@PathVariable Long scriptId,
                                            @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String desc = (String) body.get("description");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", body);
        try {
            return ApiResponse.ok(presetService.create(scriptId, name, desc, params));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        }
    }

    /**
     * 更新一个预设。
     *
     * @param scriptId 脚本 ID
     * @param presetId 预设 ID
     * @param body 请求体
     * @return 更新后的预设
     */
    @PutMapping("/{presetId}")
    public ApiResponse<ScriptPreset> update(@PathVariable Long scriptId,
                                            @PathVariable Long presetId,
                                            @RequestBody Map<String, Object> body) {
        try {
            String name = (String) body.get("name");
            String desc = (String) body.get("description");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", body);
            return ApiResponse.ok(presetService.update(scriptId, presetId, name, desc, params));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        }
    }

    /**
     * 删除一个预设。
     *
     * @param scriptId 脚本 ID
     * @param presetId 预设 ID
     * @return 成功响应
     */
    @DeleteMapping("/{presetId}")
    public ApiResponse<Void> delete(@PathVariable Long scriptId, @PathVariable Long presetId) {
        presetService.delete(scriptId, presetId);
        return ApiResponse.ok();
    }

    /**
     * 便捷接口：仅返回预设展开后的参数 Map，用于执行抽屉的快速应用。
     *
     * @param scriptId 脚本 ID
     * @param presetId 预设 ID
     * @return 参数 Map
     */
    @PostMapping("/{presetId}/apply")
    public ApiResponse<Map<String, String>> apply(@PathVariable Long scriptId, @PathVariable Long presetId) {
        ScriptPreset p = presetService.get(scriptId, presetId);
        if (p == null) return ApiResponse.error("preset not found");
        return ApiResponse.ok(presetService.applyParams(p));
    }
}