package com.bigdata.scriptbox.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 租户管理接口。
 *
 * <p>租户（Tenant）= Kerberos 主体（principal） + keytab 文件 + 标签。脚本
 * 执行时按租户加载对应环境，所以这里也是「测试 kinit 连通性」入口。
 */
@RestController
@RequestMapping("/api/tenants")
@Slf4j
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;
    private final ScriptBoxProperties props;
    private final ExecutionHistoryMapper historyMapper;

    /**
     * 列出全部租户。
     */
    @GetMapping
    public ApiResponse<List<Tenant>> list() {
        return ApiResponse.ok(tenantService.listAll());
    }

    /**
     * 查询单个租户详情。
     */
    @GetMapping("/{id}")
    public ApiResponse<Tenant> get(@PathVariable Long id) {
        Tenant t = tenantService.getById(id);
        if (t == null) return ApiResponse.error("tenant not found");
        return ApiResponse.ok(t);
    }

    /**
     * 创建一个租户。
     */
    @PostMapping
    public ApiResponse<Tenant> create(@RequestBody Tenant tenant) {
        Tenant saved = tenantService.create(tenant);
        log.info("新增租户，tenant={}", saved.getName());
        return ApiResponse.ok(saved);
    }

    /**
     * 更新一个租户。
     */
    @PutMapping("/{id}")
    public ApiResponse<Tenant> update(@PathVariable Long id, @RequestBody Tenant tenant) {
        tenant.setId(id);
        Tenant saved = tenantService.update(tenant);
        log.info("更新租户，tenant={}", saved.getName());
        return ApiResponse.ok(saved);
    }

    /**
     * 删除一个租户。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        tenantService.delete(id);
        log.info("删除租户，tenantId={}", id);
        return ApiResponse.ok();
    }

    /**
     * V2: 引用此租户的执行历史行数，用于删除确认对话框展示爆炸半径。
     */
    @GetMapping("/{id}/related-counts")
    public ApiResponse<Map<String, Long>> relatedCounts(@PathVariable Long id) {
        Map<String, Long> out = new HashMap<>();
        out.put("historyCount",
                historyMapper.selectCount(new QueryWrapper<ExecutionHistory>().eq("tenant_id", id)));
        return ApiResponse.ok(out);
    }

    /**
     * 启用 / 停用一个租户。
     */
    @PostMapping("/{id}/enabled")
    public ApiResponse<Tenant> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        Tenant t = tenantService.setEnabled(id, enabled);
        if (t == null) return ApiResponse.error("tenant not found");
        log.info("租户启停切换，tenantId={}，enabled={}", id, enabled);
        return ApiResponse.ok(t);
    }

    /**
     * 上传并绑定 keytab 文件到租户。
     *
     * <p>服务端会落盘到受控目录，并把相对路径写回数据库。
     */
    @PostMapping(value = "/{id}/keytab", consumes = "multipart/form-data")
    public ApiResponse<Tenant> uploadKeytab(@PathVariable Long id,
                                            @RequestParam("file") MultipartFile file) throws IOException {
        String path = tenantService.saveKeytab(id, file);
        Tenant t = tenantService.attachKeytab(id, path);
        // keytab 文件路径不算敏感，但文件本身是；记录路径与租户即可
        log.info("已上传 keytab，tenantId={}，keytabPath={}", id, path);
        return ApiResponse.ok(t);
    }

    /**
     * 测试租户连通性：mock 模式返回模拟输出；真实模式跑 {@code kinit -kt} +
     * {@code klist -e}。
     */
    @PostMapping("/{id}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long id) {
        Tenant t = tenantService.getById(id);
        if (t == null) return ApiResponse.error("tenant not found");
        Map<String, Object> result = new HashMap<>();
        if (props.isMock()) {
            // mock 模式下避免触碰真实 kinit；给一份假输出
            result.put("mode", "mock");
            result.put("ok", true);
            result.put("stdout", "Mock kinit OK for principal " + t.getPrincipal() + "\nMock klist:\n  Ticket cache: FILE:/tmp/krb5cc_mock\n  Default principal: " + t.getPrincipal() + "\n");
            result.put("stderr", "");
            log.info("租户连通性测试 (mock)，tenant={}", t.getName());
            return ApiResponse.ok(result);
        }
        // 真实模式
        if (t.getKeytabPath() == null || t.getKeytabPath().isBlank()) {
            log.warn("租户连通性测试失败：未配置 keytab，tenant={}", t.getName());
            return ApiResponse.error("keytab not configured for tenant");
        }
        if (!Files.exists(Paths.get(t.getKeytabPath()))) {
            log.warn("租户连通性测试失败：keytab 文件缺失，tenant={}", t.getName());
            return ApiResponse.error("keytab file missing: " + t.getKeytabPath());
        }
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
                log.warn("租户连通性测试失败：kinit 退出码={}，tenant={}", code, t.getName());
                return ApiResponse.ok(result);
            }
            ProcessBuilder klist = new ProcessBuilder("klist");
            klist.redirectErrorStream(true);
            Process kp = klist.start();
            result.put("klist", new String(kp.getInputStream().readAllBytes()));
            kp.waitFor();
            result.put("ok", true);
            log.info("租户连通性测试通过，tenant={}", t.getName());
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.warn("租户连通性测试异常，tenant={}，error={}", t.getName(), e.getMessage());
            return ApiResponse.error("test failed: " + e.getMessage());
        }
    }
}