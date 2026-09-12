package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
        return ApiResponse.ok(tenantService.relatedCounts(id));
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
     *
     * <p>进程编排已下沉到 {@link TenantService#testConnectivity(Long)}，Controller
     * 只做协议转换。
     */
    @PostMapping("/{id}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long id) {
        TenantService.TenantConnectivity result = tenantService.testConnectivity(id);
        if (result == null) return ApiResponse.error("tenant not found");
        return ApiResponse.ok(result.details());
    }

    /**
     * V3 (PR-1): 把租户认证标记为"待重新测试"。
     *
     * <p>前端在用户修改 principal 或 keytab 后调用，让 UI 能直接显示横幅
     * "Principal 或 Keytab 已变更，请重新测试认证"，避免在 DB 状态与
     * UI 状态之间产生歧义。
     */
    @PostMapping("/{id}/mark-stale")
    public ApiResponse<Tenant> markStale(@PathVariable Long id) {
        Tenant t = tenantService.markAuthStale(id);
        if (t == null) return ApiResponse.error("tenant not found");
        return ApiResponse.ok(t);
    }
}