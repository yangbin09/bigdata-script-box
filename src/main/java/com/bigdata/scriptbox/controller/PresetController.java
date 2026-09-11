package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ScriptPreset;
import com.bigdata.scriptbox.service.PresetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scripts/{scriptId}/presets")
public class PresetController {

    @Autowired private PresetService presetService;

    @GetMapping
    public ApiResponse<List<ScriptPreset>> list(@PathVariable Long scriptId) {
        return ApiResponse.ok(presetService.listByScript(scriptId));
    }

    @GetMapping("/summary")
    public ApiResponse<List<Map<String, Object>>> summary(@PathVariable Long scriptId) {
        return ApiResponse.ok(presetService.listSummary(scriptId));
    }

    @GetMapping("/{presetId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long scriptId, @PathVariable Long presetId) {
        ScriptPreset p = presetService.get(scriptId, presetId);
        if (p == null) return ApiResponse.error("preset not found");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("preset", p);
        data.put("params", presetService.applyParams(p));
        return ApiResponse.ok(data);
    }

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

    @DeleteMapping("/{presetId}")
    public ApiResponse<Void> delete(@PathVariable Long scriptId, @PathVariable Long presetId) {
        presetService.delete(scriptId, presetId);
        return ApiResponse.ok();
    }

    /** Convenience: returns just the params of a preset so the execute drawer can apply it. */
    @PostMapping("/{presetId}/apply")
    public ApiResponse<Map<String, String>> apply(@PathVariable Long scriptId, @PathVariable Long presetId) {
        ScriptPreset p = presetService.get(scriptId, presetId);
        if (p == null) return ApiResponse.error("preset not found");
        return ApiResponse.ok(presetService.applyParams(p));
    }
}