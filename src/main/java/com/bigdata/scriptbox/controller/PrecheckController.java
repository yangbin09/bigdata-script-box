package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.PrecheckService;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 执行前检查接口。
 *
 * <p>仅做一件事：跑 PreCheck（Kerberos / 命令 / 文件 / 目录可写）并返回结果，
 * 不实际启动脚本。供前端在「执行」按钮点击前做预检，或者独立调试使用。
 */
@RestController
@RequestMapping("/api/scripts/{scriptId}/precheck")
public class PrecheckController {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private PrecheckService precheckService;

    /**
     * 触发执行前检查。请求体：{@code { "tenantId": ... }}。
     *
     * @param scriptId 脚本 ID（路径变量）
     * @param body 请求体，含 tenantId
     * @return Precheck 结果
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> run(@PathVariable Long scriptId,
                                                @RequestBody Map<String, Object> body) {
        Script script = scriptService.getById(scriptId);
        if (script == null) return ApiResponse.error("script not found");
        Object tid = body.get("tenantId");
        Tenant tenant = null;
        if (tid instanceof Number n) {
            tenant = tenantService.getById(n.longValue());
        } else if (tid != null) {
            try { tenant = tenantService.getById(Long.parseLong(tid.toString())); }
            catch (Exception ignored) {}
        }
        return ApiResponse.ok(precheckService.run(script, tenant));
    }
}