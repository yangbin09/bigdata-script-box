package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.GlobalVariable;
import com.bigdata.scriptbox.service.GlobalVariableService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 全局变量管理接口。
 *
 * <p>提供全局变量的列表、详情、创建、更新、删除。详情接口会对敏感字段做
 * 遮罩（{@code *}），避免通过 API 泄露真实值。
 */
@RestController
@RequestMapping("/api/global-variables")
@RequiredArgsConstructor
public class GlobalVariableController {

    private final GlobalVariableService variableService;

    /**
     * 列出全部全局变量（敏感字段已遮罩）。
     *
     * @return 列表视图
     */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(variableService.listSummary());
    }

    /**
     * 获取单个全局变量详情。
     *
     * @param id 变量 ID
     * @return 详情（含遮罩后的值）
     */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        GlobalVariable v = variableService.get(id);
        if (v == null) return ApiResponse.error("variable not found");
        // 响应中的敏感值必须经过遮罩，避免泄露
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

    /**
     * 创建一个全局变量。
     *
     * @param body 请求体，含 variableKey / variableValue / description / sensitive / enabled
     * @return 创建后的实体
     */
    @PostMapping
    public ApiResponse<GlobalVariable> create(@RequestBody Map<String, Object> body) {
        // 异常处理走 GlobalExceptionHandler（IllegalArgumentException → ApiResponse.error）
        String key = (String) body.get("variableKey");
        String val = (String) body.get("variableValue");
        String desc = (String) body.get("description");
        boolean sensitive = Boolean.TRUE.equals(body.get("sensitive"));
        boolean enabled = !body.containsKey("enabled") || Boolean.TRUE.equals(body.get("enabled"));
        return ApiResponse.ok(variableService.create(key, val, desc, sensitive, enabled));
    }

    /**
     * 更新一个全局变量。
     *
     * @param id 变量 ID
     * @param body 请求体
     * @return 更新后的实体
     */
    @PutMapping("/{id}")
    public ApiResponse<GlobalVariable> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String key = (String) body.get("variableKey");
        String val = (String) body.get("variableValue");
        String desc = (String) body.get("description");
        boolean sensitive = Boolean.TRUE.equals(body.get("sensitive"));
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return ApiResponse.ok(variableService.update(id, key, val, desc, sensitive, enabled));
    }

    /**
     * 删除一个全局变量。
     *
     * @param id 变量 ID
     * @return 成功响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        variableService.delete(id);
        return ApiResponse.ok();
    }
}