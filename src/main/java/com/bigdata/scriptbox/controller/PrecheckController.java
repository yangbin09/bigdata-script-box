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

@RestController
@RequestMapping("/api/scripts/{scriptId}/precheck")
public class PrecheckController {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private PrecheckService precheckService;

    /**
     * Run the pre-execution checks without actually executing the script.
     * Body: { "tenantId": ... }
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