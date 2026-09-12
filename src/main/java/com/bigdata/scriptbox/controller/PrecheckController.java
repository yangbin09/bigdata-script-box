package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.PrecheckService;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class PrecheckController {

    private final ScriptService scriptService;
    private final TenantService tenantService;
    private final PrecheckService precheckService;

    /**
     * 触发执行前检查。请求体：{@code { "tenantId": ... }}。
     *
     * @param scriptId 脚本 ID（路径变量）
     * @param body 请求体，含 tenantId
     * @return Precheck 结果（{@link PrecheckService.PrecheckReport}，字段名与历史 Map 一致）
     */
    @PostMapping
    public ApiResponse<PrecheckService.PrecheckReport> run(@PathVariable Long scriptId,
                                                           @RequestBody Map<String, Object> body) {
        Script script = scriptService.getById(scriptId);
        if (script == null) return ApiResponse.error("script not found");
        Tenant tenant = resolveTenant(body.get("tenantId"));
        return ApiResponse.ok(precheckService.run(script, tenant));
    }

    /** tenantId 允许是数字或数字字符串；缺失 / 非法时返回 null（Kerberos 策略会判失败）。 */
    private Tenant resolveTenant(Object raw) {
        if (raw instanceof Number n) {
            return tenantService.getById(n.longValue());
        }
        if (raw != null) {
            try {
                return tenantService.getById(Long.parseLong(raw.toString().trim()));
            } catch (NumberFormatException ignored) {
                // 非法 tenantId 视为未提供
            }
        }
        return null;
    }
}
