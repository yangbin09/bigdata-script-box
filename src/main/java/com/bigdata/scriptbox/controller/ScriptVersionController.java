package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ScriptVersion;
import com.bigdata.scriptbox.service.ScriptVersionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scripts/{scriptId}/versions")
public class ScriptVersionController {

    @Autowired private ScriptVersionService versionService;

    @GetMapping
    public ApiResponse<List<ScriptVersion>> list(@PathVariable Long scriptId) {
        return ApiResponse.ok(versionService.listByScript(scriptId));
    }

    @GetMapping("/{versionNo}")
    public ApiResponse<ScriptVersion> get(@PathVariable Long scriptId, @PathVariable Integer versionNo) {
        ScriptVersion v = versionService.get(scriptId, versionNo);
        if (v == null) return ApiResponse.error("version not found");
        return ApiResponse.ok(v);
    }

    @PostMapping("/{versionNo}/rollback")
    public ApiResponse<Map<String, Object>> rollback(@PathVariable Long scriptId,
                                                     @PathVariable Integer versionNo) {
        try {
            ScriptVersion created = versionService.rollback(scriptId, versionNo);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("newVersion", created);
            return ApiResponse.ok(out);
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        } catch (Exception ex) {
            return ApiResponse.error("rollback failed: " + ex.getMessage());
        }
    }
}