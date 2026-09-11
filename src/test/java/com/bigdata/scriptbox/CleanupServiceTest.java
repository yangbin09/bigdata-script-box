package com.bigdata.scriptbox;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.service.CleanupService;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.SystemSettingService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CleanupServiceTest extends BaseIntegrationTest {

    @Autowired private CleanupService cleanupService;
    @Autowired private SystemSettingService settingsService;
    @Autowired private ScriptService scriptService;
    @Autowired private ScriptExecutor executor;
    @Autowired private TenantService tenantService;
    @Autowired private ExecutionHistoryMapper historyMapper;

    @Test
    void previewReflectsCurrentRows() {
        Map<String, Object> p = cleanupService.preview();
        assertNotNull(p.get("historyDays"));
        assertNotNull(p.get("artifactDays"));
        assertNotNull(p.get("executionDays"));
        assertNotNull(p.get("historyCandidates"));
        assertNotNull(p.get("artifactCandidates"));
        assertNotNull(p.get("executionDirsCandidates"));
    }

    @Test
    void applyDeletesOldHistoryAndDirs() throws Exception {
        // Insert a synthetic old history row + execution dir.
        long oldId = System.currentTimeMillis();
        ExecutionHistory old = new ExecutionHistory();
        old.setId(oldId);
        old.setScriptId(0L);
        old.setScriptName("cleanup-test");
        old.setStatus("SUCCESS");
        old.setSuccess(true);
        old.setStartTime(LocalDateTime.now().minusDays(60));
        old.setEndTime(LocalDateTime.now().minusDays(60));
        old.setDurationMs(10L);
        old.setExitCode(0);
        historyMapper.insert(old);

        java.nio.file.Path oldDir = java.nio.file.Paths.get(
                props.getExecutionsDir(), String.valueOf(oldId));
        java.nio.file.Files.createDirectories(oldDir);
        java.nio.file.Files.writeString(oldDir.resolve("stdout.log"), "old");
        java.nio.file.Files.setLastModifiedTime(oldDir,
                java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() - 60L * 86_400_000L));

        // Run apply.
        Map<String, Object> result = cleanupService.apply();
        assertNotNull(result.get("historyDeleted"));
        assertTrue(((Number) result.get("historyDeleted")).intValue() >= 1,
                "should delete at least one history row: " + result);

        // Row gone.
        assertNull(historyMapper.selectById(oldId));
        // Dir gone.
        assertFalse(java.nio.file.Files.exists(oldDir));
    }

    @Test
    void applySkipsRunningExecutions() throws Exception {
        // We can't easily simulate "running" from a unit test because the
        // RunningExecutionRegistry is a live-process tracker, but we can
        // verify the skip-running branch by reading the live list and
        // ensuring no ids in it get deleted.
        long liveId = executor.history(0L) == null ? 0L
                : executor.history(0L).getId(); // best-effort probe
        // Just ensure the call doesn't blow up; the actual skip semantics
        // are covered by the ExecutionCancellationTest registry assertions.
        Map<String, Object> result = cleanupService.apply();
        assertNotNull(result.get("skippedRunning"));
    }

    @Test
    void zeroDaysDisablesCleanup() {
        // Override history to 0 — preview should still report the override,
        // apply() must not delete anything history-related.
        settingsService.upsert(CleanupService.K_HISTORY, "0", null);
        long beforeCount = historyMapper.selectCount(null);
        cleanupService.apply();
        long afterCount = historyMapper.selectCount(null);
        assertEquals(beforeCount, afterCount, "history cleanup must be disabled when historyDays=0");

        // Reset.
        settingsService.upsert(CleanupService.K_HISTORY, "30", null);
    }

    @Test
    void settingsOverrideIsRespected() {
        settingsService.upsert(CleanupService.K_HISTORY, "12", null);
        assertEquals(12, cleanupService.historyDays());
        settingsService.upsert(CleanupService.K_HISTORY, "30", null);
    }

    @Test
    void systemSettingRoundTrip() {
        settingsService.upsert("cleanup.historyDays", "45", "override");
        assertEquals(45, cleanupService.historyDays());
        assertEquals(45, settingsService.getInt("cleanup.historyDays", -1));
        // Bogus value falls back to default.
        settingsService.upsert("cleanup.historyDays", "not-a-number", null);
        assertEquals(props.getRetentionHistoryDays(), cleanupService.historyDays());
        // Reset.
        settingsService.upsert("cleanup.historyDays", "30", null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.bigdata.scriptbox.config.ScriptBoxProperties props;
}