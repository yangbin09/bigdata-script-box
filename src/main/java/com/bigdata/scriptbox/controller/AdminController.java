package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.CleanupHistory;
import com.bigdata.scriptbox.entity.SystemSetting;
import com.bigdata.scriptbox.service.CleanupExecutor;
import com.bigdata.scriptbox.service.CleanupService;
import com.bigdata.scriptbox.service.PreviewStore;
import com.bigdata.scriptbox.service.SystemSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理接口控制器。
 *
 * <p>仅做两件事：
 * <ul>
 *   <li>人工数据清理：完全手动的「预览 → 确认 → 执行」流程，所有删除都必须
 *       走这一步；</li>
 *   <li>系统设置：持久化一些可热更新的键值配置（清理保留天数等）。</li>
 * </ul>
 *
 * <p>前端永远不发路径：受控目录由后端从 {@code scriptbox.executions-dir} /
 * {@code scriptbox.logs-dir} 派生并写入预览快照。设置写入走白名单，不允许
 * 任意键覆盖。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Autowired private CleanupService cleanupService;
    @Autowired private SystemSettingService settingsService;

    // ----------------------------------------------------------------------
    // 局部异常处理：本控制器的清理接口对前端契约保留 "PREVIEW_EXPIRED: ..." /
    // "BAD_REQUEST: ..." / "EXECUTE_FAILED: ..." 前缀，便于前端按 code 前缀分流。
    // 不污染全局 GlobalExceptionHandler 的语义。
    // ----------------------------------------------------------------------

    @ExceptionHandler(PreviewStore.PreviewExpiredException.class)
    public ApiResponse<Void> handlePreviewExpired(PreviewStore.PreviewExpiredException ex) {
        log.warn("cleanup preview expired: {}", ex.getMessage());
        return ApiResponse.error("PREVIEW_EXPIRED: " + ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("cleanup bad request: {}", ex.getMessage());
        return ApiResponse.error("BAD_REQUEST: " + ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleAny(Exception ex) {
        log.error("cleanup execute failed", ex);
        return ApiResponse.error("EXECUTE_FAILED: " + ex.getMessage());
    }

    // ----------------------------------------------------------------------
    // 清理
    // ----------------------------------------------------------------------

    /**
     * 生成清理预览快照。请求体：
     * <pre>{ "historyDays": 30, "artifactDays": 30,
     *   "executionDays": 30, "logDays": 3 }</pre>
     *
     * <p>小于 0 的值会被夹到 0（即禁用对应类别）。
     *
     * @param body 请求体（可缺省，缺省时使用当前系统设置）
     * @return 预览详情
     */
    @PostMapping("/cleanup/preview")
    public ApiResponse<PreviewStore.CleanupPreview> cleanupPreview(
            @RequestBody(required = false) Map<String, Object> body) {
        int h = readInt(body, "historyDays",   cleanupService.historyDays());
        int a = readInt(body, "artifactDays",  cleanupService.artifactDays());
        int e = readInt(body, "executionDays", cleanupService.executionDays());
        int l = readInt(body, "logDays",       cleanupService.logDays());
        log.info("开始生成数据清理预览 historyDays={} artifactDays={} executionDays={} logDays={}",
                h, a, e, l);
        PreviewStore.CleanupPreview preview = cleanupService.preview(h, a, e, l);
        log.info("数据清理预览生成完成，previewId={}，候选数量={}，预计释放={}字节",
                preview.getPreviewId(),
                preview.totals.executionDirCount + preview.totals.artifactCount + preview.totals.logCount,
                preview.totals.totalBytes);
        return ApiResponse.ok(preview);
    }

    /**
     * 应用此前预览的快照。请求体：
     * <pre>{ "previewId": "<uuid>", "confirmToken": "CLEAN" }</pre>
     *
     * @param body 请求体
     * @return 清理结果报告
     */
    @PostMapping("/cleanup/execute")
    public ApiResponse<CleanupExecutor.CleanupReport> cleanupExecute(
            @RequestBody Map<String, Object> body) {
        String previewId = body == null ? null : (String) body.get("previewId");
        String token     = body == null ? null : (String) body.get("confirmToken");
        if (previewId == null || previewId.isBlank())
            return ApiResponse.error("previewId is required");
        if (token == null || !CleanupService.CONFIRM_TOKEN.equals(token))
            return ApiResponse.error("confirmation token mismatch: type CLEAN exactly");
        log.info("用户确认执行手动数据清理，previewId={}", previewId);
        CleanupExecutor.CleanupReport report = cleanupService.execute(previewId, token);
        log.info("手动数据清理完成，删除={}，跳过={}，失败={}，释放={}字节",
                report.executionDeleted + report.artifactDeleted + report.logDeleted + report.historyDeleted,
                report.skippedCount(), report.failedCount(), report.bytesFreed);
        return ApiResponse.ok(report);
    }

    /**
     * 获取最近 N 条清理历史。
     *
     * @param limit 上限条数（默认 20）
     * @return 清理历史列表
     */
    @GetMapping("/cleanup/history")
    public ApiResponse<List<CleanupHistory>> cleanupHistory(
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        return ApiResponse.ok(cleanupService.recentHistory(Math.max(1, Math.min(limit, 200))));
    }

    // ----------------------------------------------------------------------
    // 设置（键值对）
     // ----------------------------------------------------------------------

    /**
     * 列出全部持久化设置。
     *
     * @return 键 → 值的 Map
     */
    @GetMapping("/settings")
    public ApiResponse<Map<String, Object>> listSettings() {
        List<SystemSetting> rows = settingsService.listAll();
        Map<String, Object> map = new HashMap<>();
        for (SystemSetting s : rows) map.put(s.getSettingKey(), s.getSettingValue());
        return ApiResponse.ok(map);
    }

    /**
     * 批量更新设置。请求体：{@code {"cleanup.historyDays": "30", ...}}。
     *
     * @param body 键 → 字符串值 的 Map
     * @return 实际写入的键值对
     */
    @PutMapping("/settings")
    public ApiResponse<Map<String, String>> updateSettings(@RequestBody Map<String, String> body) {
        if (body == null || body.isEmpty()) return ApiResponse.ok(Collections.<String, String>emptyMap());
        for (String key : body.keySet()) {
            if (!isAllowedKey(key)) return ApiResponse.error("unknown setting key: " + key);
        }
        Map<String, String> applied = new HashMap<>();
        for (Map.Entry<String, String> e : body.entrySet()) {
            settingsService.upsert(e.getKey(), e.getValue(), null);
            applied.put(e.getKey(), e.getValue());
        }
        return ApiResponse.ok(applied);
    }

    /**
     * 白名单校验：仅放行清理保留天数相关键。
     *
     * @param key 配置键
     * @return 是否允许写入
     */
    private boolean isAllowedKey(String key) {
        return CleanupService.K_HISTORY.equals(key)
                || CleanupService.K_ARTIFACT.equals(key)
                || CleanupService.K_EXECUTION.equals(key)
                || CleanupService.K_LOG.equals(key);
    }

    /**
     * 从请求体里读取整数值，缺省或非法时返回 {@code defaultValue}。
     *
     * @param body 请求体（可空）
     * @param key 字段名
     * @param defaultValue 默认值
     * @return 整数结果（最小为 0）
     */
    private int readInt(Map<String, Object> body, String key, int defaultValue) {
        if (body == null) return defaultValue;
        Object v = body.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return Math.max(0, ((Number) v).intValue());
        try { return Math.max(0, Integer.parseInt(v.toString().trim())); }
        catch (NumberFormatException nfe) { return defaultValue; }
    }
}