package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.SystemSetting;
import com.bigdata.scriptbox.service.CleanupService;
import com.bigdata.scriptbox.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * V2: admin endpoints — cleanup preview/apply and runtime settings.
 *
 * <p>Cleanup preview/apply surfaces are deliberately separate so the FE
 * can show a confirmation dialog with the candidate counts before the
 * operator commits. Both endpoints are GET-style (preview) /
 * POST-style (apply) — apply is idempotent.
 *
 * <p>Settings are persisted via {@link SystemSettingService}. Keys are
 * dotted namespaced (e.g. {@code cleanup.historyDays}). Values are
 * strings; the reader coerces to int/long/boolean as needed.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired private CleanupService cleanupService;
    @Autowired private SystemSettingService settingsService;

    @GetMapping("/cleanup/preview")
    public ApiResponse<Map<String, Object>> cleanupPreview() {
        return ApiResponse.ok(cleanupService.preview());
    }

    @PostMapping("/cleanup/apply")
    public ApiResponse<Map<String, Object>> cleanupApply() {
        return ApiResponse.ok(cleanupService.apply());
    }

    @GetMapping("/settings")
    public ApiResponse<Map<String, Object>> listSettings() {
        List<SystemSetting> rows = settingsService.listAll();
        Map<String, Object> map = new HashMap<>();
        for (SystemSetting s : rows) map.put(s.getSettingKey(), s.getSettingValue());
        return ApiResponse.ok(map);
    }

    /** Bulk update; missing keys are removed. Body: {@code {"cleanup.historyDays": "30", ...}} */
    @PutMapping("/settings")
    public ApiResponse<Map<String, String>> updateSettings(@RequestBody Map<String, String> body) {
        if (body == null || body.isEmpty()) return ApiResponse.ok(Collections.<String, String>emptyMap());
        // Only accept well-known keys; refuse arbitrary writes so callers
        // can't accidentally clobber unrelated state.
        for (String key : body.keySet()) {
            if (!isAllowedKey(key)) {
                return ApiResponse.error("unknown setting key: " + key);
            }
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
}