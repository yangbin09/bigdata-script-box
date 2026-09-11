package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.TenantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    @Autowired private TenantService tenantService;
    @Autowired private ScriptBoxProperties props;

    @GetMapping
    public ApiResponse<List<Tenant>> list() {
        return ApiResponse.ok(tenantService.listAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<Tenant> get(@PathVariable Long id) {
        Tenant t = tenantService.getById(id);
        if (t == null) return ApiResponse.error("tenant not found");
        return ApiResponse.ok(t);
    }

    @PostMapping
    public ApiResponse<Tenant> create(@RequestBody Tenant tenant) {
        return ApiResponse.ok(tenantService.create(tenant));
    }

    @PutMapping("/{id}")
    public ApiResponse<Tenant> update(@PathVariable Long id, @RequestBody Tenant tenant) {
        tenant.setId(id);
        return ApiResponse.ok(tenantService.update(tenant));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        tenantService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<Tenant> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        Tenant t = tenantService.setEnabled(id, enabled);
        if (t == null) return ApiResponse.error("tenant not found");
        return ApiResponse.ok(t);
    }

    @PostMapping(value = "/{id}/keytab", consumes = "multipart/form-data")
    public ApiResponse<Tenant> uploadKeytab(@PathVariable Long id,
                                            @RequestParam("file") MultipartFile file) throws IOException {
        String path = tenantService.saveKeytab(id, file);
        return ApiResponse.ok(tenantService.attachKeytab(id, path));
    }

    /**
     * Test tenant: in mock mode, returns success with simulated klist output.
     * In real mode, attempts `kinit -kt keytab principal` and `klist -e`.
     */
    @PostMapping("/{id}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long id) {
        Tenant t = tenantService.getById(id);
        if (t == null) return ApiResponse.error("tenant not found");
        Map<String, Object> result = new HashMap<>();
        if (props.isMock()) {
            result.put("mode", "mock");
            result.put("ok", true);
            result.put("stdout", "Mock kinit OK for principal " + t.getPrincipal() + "\nMock klist:\n  Ticket cache: FILE:/tmp/krb5cc_mock\n  Default principal: " + t.getPrincipal() + "\n");
            result.put("stderr", "");
            return ApiResponse.ok(result);
        }
        // Real mode
        if (t.getKeytabPath() == null || t.getKeytabPath().isBlank())
            return ApiResponse.error("keytab not configured for tenant");
        if (!Files.exists(Paths.get(t.getKeytabPath())))
            return ApiResponse.error("keytab file missing: " + t.getKeytabPath());
        try {
            ProcessBuilder pb = new ProcessBuilder("kinit", "-kt", t.getKeytabPath(), t.getPrincipal());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            result.put("kinitExit", code);
            result.put("stdout", out);
            if (code != 0) {
                result.put("ok", false);
                return ApiResponse.ok(result);
            }
            ProcessBuilder klist = new ProcessBuilder("klist");
            klist.redirectErrorStream(true);
            Process kp = klist.start();
            result.put("klist", new String(kp.getInputStream().readAllBytes()));
            kp.waitFor();
            result.put("ok", true);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.error("test failed: " + e.getMessage());
        }
    }
}