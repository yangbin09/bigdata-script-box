package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.GlobalVariable;
import com.bigdata.scriptbox.service.GlobalVariableService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/global-variables")
public class GlobalVariableController {

    @Autowired private GlobalVariableService variableService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(variableService.listSummary());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        GlobalVariable v = variableService.get(id);
        if (v == null) return ApiResponse.error("variable not found");
        // Mask sensitive values for the API response.
        return ApiResponse.ok(Map.of(
                "id", v.getId(),
                "variableKey", v.getVariableKey(),
                "variableValue", variableService.maskedValue(v),
                "hasValue", v.getVariableValue() != null && !v.getVariableValue().isEmpty(),
                "description", v.getDescription() == null ? "" : v.getDescription(),
                "sensitive", Boolean.TRUE.equals(v.getSensitive()),
                "enabled", v.getEnabled() == null ? Boolean.TRUE : v.getEnabled()
        ));
    }

    @PostMapping
    public ApiResponse<GlobalVariable> create(@RequestBody Map<String, Object> body) {
        try {
            String key = (String) body.get("variableKey");
            String val = (String) body.get("variableValue");
            String desc = (String) body.get("description");
            boolean sensitive = Boolean.TRUE.equals(body.get("sensitive"));
            boolean enabled = !body.containsKey("enabled") || Boolean.TRUE.equals(body.get("enabled"));
            return ApiResponse.ok(variableService.create(key, val, desc, sensitive, enabled));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<GlobalVariable> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String key = (String) body.get("variableKey");
            String val = (String) body.get("variableValue");
            String desc = (String) body.get("description");
            boolean sensitive = Boolean.TRUE.equals(body.get("sensitive"));
            boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
            return ApiResponse.ok(variableService.update(id, key, val, desc, sensitive, enabled));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        variableService.delete(id);
        return ApiResponse.ok();
    }
}