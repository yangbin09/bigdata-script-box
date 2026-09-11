package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.CleanupHistory;
import com.bigdata.scriptbox.entity.SystemSetting;
import com.bigdata.scriptbox.service.CleanupExecutor;
import com.bigdata.scriptbox.service.CleanupService;
import com.bigdata.scriptbox.service.PreviewStore;
import com.bigdata.scriptbox.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V2: admin endpoints for manual cleanup + persisted settings.
 *
 * <p>Cleanup is fully manual. Every delete must travel through preview →
 * execute. The frontend only ever sends:
 * <ul>
 *   <li>retention days (historyDays / artifactDays / executionDays / logDays),</li>
 *   <li>a {@code previewId} obtained from the preview call, and</li>
 *   <li>the literal confirmation token {@code "CLEAN"}.</li>
 * </ul>
 * The frontend never sends a path; the controlled roots are derived from
 * {@code scriptbox.executions-dir} / {@code scriptbox.logs-dir} inside
 * Java and stamped into the preview snapshot.
 *
 * <p>Settings are persisted via {@link SystemSettingService}; writes are
 * allow-listed so callers can't accidentally clobber unrelated state.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired private CleanupService cleanupService;
    @Autowired private SystemSettingService settingsService;

    // ----------------------------------------------------------------------
    // Cleanup
    // ----------------------------------------------------------------------

    /**
     * Build a preview snapshot. Body shape:
     * <pre>{ "historyDays": 30, "artifactDays": 30,
     *   "executionDays": 30, "logDays": 7 }</pre>
     * Anything below zero is clamped to zero (= disabled for that category).
     */
    @PostMapping("/cleanup/preview")
    public ApiResponse<PreviewStore.CleanupPreview> cleanupPreview(
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            int h = readInt(body, "historyDays",   cleanupService.historyDays());
            int a = readInt(body, "artifactDays",  cleanupService.artifactDays());
            int e = readInt(body, "executionDays", cleanupService.executionDays());
            int l = readInt(body, "logDays",       cleanupService.logDays());
            return ApiResponse.ok(cleanupService.preview(h, a, e, l));
        } catch (PreviewStore.PreviewExpiredException pee) {
            return ApiResponse.error("PREVIEW_EXPIRED: " + pee.getMessage());
        } catch (IllegalArgumentException iae) {
            return ApiResponse.error("BAD_REQUEST: " + iae.getMessage());
        }
    }

    /**
     * Apply a previously-previewed snapshot. Body shape:
     * <pre>{ "previewId": "<uuid>", "confirmToken": "CLEAN" }</pre>
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
        try {
            return ApiResponse.ok(cleanupService.execute(previewId, token));
        } catch (PreviewStore.PreviewExpiredException pee) {
            return ApiResponse.error("PREVIEW_EXPIRED: " + pee.getMessage());
        } catch (IllegalArgumentException iae) {
            return ApiResponse.error("BAD_REQUEST: " + iae.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("EXECUTE_FAILED: " + e.getMessage());
        }
    }

    /** Most-recent N cleanup records. */
    @GetMapping("/cleanup/history")
    public ApiResponse<List<CleanupHistory>> cleanupHistory(
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        return ApiResponse.ok(cleanupService.recentHistory(limit));
    }

    // ----------------------------------------------------------------------
    // Settings (persisted key/value)
    // ----------------------------------------------------------------------

    @GetMapping("/settings")
    public ApiResponse<Map<String, Object>> listSettings() {
        List<SystemSetting> rows = settingsService.listAll();
        Map<String, Object> map = new HashMap<>();
        for (SystemSetting s : rows) map.put(s.getSettingKey(), s.getSettingValue());
        return ApiResponse.ok(map);
    }

    /** Bulk update. Body: {@code {"cleanup.historyDays": "30", ...}}. */
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

    private boolean isAllowedKey(String key) {
        return CleanupService.K_HISTORY.equals(key)
                || CleanupService.K_ARTIFACT.equals(key)
                || CleanupService.K_EXECUTION.equals(key)
                || CleanupService.K_LOG.equals(key);
    }

    private int readInt(Map<String, Object> body, String key, int defaultValue) {
        if (body == null) return defaultValue;
        Object v = body.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number) return Math.max(0, ((Number) v).intValue());
        try { return Math.max(0, Integer.parseInt(v.toString().trim())); }
        catch (NumberFormatException nfe) { return defaultValue; }
    }
}