package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.ExecutionArtifact;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.mapper.ExecutionArtifactMapper;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * V2: auto-cleanup. Two surfaces:
 *
 * <ol>
 *   <li>{@link #preview()} — counts and a per-bucket breakdown without
 *       deleting anything. The settings UI calls this to show the operator
 *       exactly what will be removed before they confirm.</li>
 *   <li>{@link #apply()} — actually deletes. Backs the cron job AND the
 *       "cleanup now" admin button.</li>
 * </ol>
 *
 * <p>Retention is read from {@link SystemSettingService} first (keys
 * {@code cleanup.historyDays} etc.), falling back to
 * {@link ScriptBoxProperties} defaults. {@code 0} disables a category.
 *
 * <p>Safety: any execution still in {@link RunningExecutionRegistry} is
 * NEVER deleted, even if it's older than the retention window — we'd race
 * with the live process and could leave the on-disk dir in an
 * unrecoverable state. The preview surfaces the skip count so the
 * operator knows.
 */
@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    public static final String K_HISTORY = "cleanup.historyDays";
    public static final String K_ARTIFACT = "cleanup.artifactDays";
    public static final String K_EXECUTION = "cleanup.executionDays";
    public static final String K_LOG = "cleanup.logDays";

    @Autowired private ScriptBoxProperties props;
    @Autowired private SystemSettingService settings;
    @Autowired private ExecutionHistoryMapper historyMapper;
    @Autowired private ExecutionArtifactMapper artifactMapper;
    @Autowired private RunningExecutionRegistry runningRegistry;

    /** Effective retention in days; respects override rows. 0 = disabled. */
    public int historyDays() {
        return settings.getInt(K_HISTORY, props.getRetentionHistoryDays());
    }
    public int artifactDays() {
        return settings.getInt(K_ARTIFACT, props.getRetentionArtifactDays());
    }
    public int executionDays() {
        return settings.getInt(K_EXECUTION, props.getRetentionExecutionDays());
    }
    public int logDays() {
        return settings.getInt(K_LOG, props.getRetentionLogDays());
    }

    /**
     * Compute the breakdown WITHOUT deleting anything. The result shape is:
     * <pre>
     * {
     *   "historyDays": 30, "artifactDays": 30, "executionDays": 30, "logDays": 7,
     *   "historyCandidates": 5,  "historySkippedRunning": 1,
     *   "artifactCandidates": 12,
     *   "executionDirsCandidates": 4,
     *   "oldestHistoryIso": "2026-08-01T03:14:15"
     * }
     * </pre>
     * Counts are read directly from the DB / filesystem so they reflect
     * the actual state.
     */
    public Map<String, Object> preview() {
        Map<String, Object> out = new HashMap<>();
        out.put("historyDays", historyDays());
        out.put("artifactDays", artifactDays());
        out.put("executionDays", executionDays());
        out.put("logDays", logDays());

        // History
        int hDays = historyDays();
        int historyCandidates = 0;
        int historySkippedRunning = 0;
        if (hDays > 0) {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(hDays);
            List<ExecutionHistory> rows = historyMapper.selectList(
                    new QueryWrapper<ExecutionHistory>().lt("start_time", cutoff));
            for (ExecutionHistory h : rows) {
                if (isRunning(h.getId())) historySkippedRunning++;
                else historyCandidates++;
            }
            // Oldest for UI
            List<ExecutionHistory> oldest = historyMapper.selectList(
                    new QueryWrapper<ExecutionHistory>().orderByAsc("start_time").last("LIMIT 1"));
            if (!oldest.isEmpty()) out.put("oldestHistoryIso",
                    oldest.get(0).getStartTime().atZone(ZoneId.systemDefault()).toInstant().toString());
        }
        out.put("historyCandidates", historyCandidates);
        out.put("historySkippedRunning", historySkippedRunning);

        // Artifacts
        int aDays = artifactDays();
        int artifactCandidates = 0;
        if (aDays > 0) {
            // We don't have a createdAt column on the artifact table, but
            // we can infer from the joined execution_history.start_time.
            // Simpler: any artifact whose execution's start_time is older
            // than aDays is a candidate. Use the join via IN.
            LocalDateTime cutoff = LocalDateTime.now().minusDays(aDays);
            List<ExecutionHistory> oldRuns = historyMapper.selectList(
                    new QueryWrapper<ExecutionHistory>().lt("start_time", cutoff));
            for (ExecutionHistory h : oldRuns) {
                if (isRunning(h.getId())) continue;
                Long n = artifactMapper.selectCount(new QueryWrapper<ExecutionArtifact>()
                        .eq("execution_id", h.getId()));
                artifactCandidates += n == null ? 0 : n;
            }
        }
        out.put("artifactCandidates", artifactCandidates);

        // Execution dirs
        int eDays = executionDays();
        int executionDirsCandidates = 0;
        if (eDays > 0) {
            executionDirsCandidates = countOldExecutionDirs(eDays);
        }
        out.put("executionDirsCandidates", executionDirsCandidates);

        return out;
    }

    /**
     * Apply retention: delete history rows + on-disk dirs that exceed the
     * retention window. Idempotent — running it twice in a row is a no-op
     * the second time. Returns the breakdown as in {@link #preview()} but
     * with "deleted" counts instead of "candidates".
     */
    public Map<String, Object> apply() {
        long start = System.currentTimeMillis();
        int deletedHistory = 0, deletedArtifacts = 0, deletedDirs = 0, skippedRunning = 0;
        List<Long> failedDeletes = new ArrayList<>();

        // History
        int hDays = historyDays();
        if (hDays > 0) {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(hDays);
            List<ExecutionHistory> rows = historyMapper.selectList(
                    new QueryWrapper<ExecutionHistory>().lt("start_time", cutoff));
            for (ExecutionHistory h : rows) {
                if (isRunning(h.getId())) { skippedRunning++; continue; }
                try {
                    historyMapper.deleteById(h.getId());
                    deletedHistory++;
                } catch (Exception e) {
                    failedDeletes.add(h.getId());
                }
            }
        }

        // Artifact rows
        int aDays = artifactDays();
        if (aDays > 0) {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(aDays);
            List<ExecutionHistory> oldRuns = historyMapper.selectList(
                    new QueryWrapper<ExecutionHistory>().lt("start_time", cutoff));
            for (ExecutionHistory h : oldRuns) {
                if (isRunning(h.getId())) continue;
                List<ExecutionArtifact> arts = artifactMapper.selectList(
                        new QueryWrapper<ExecutionArtifact>().eq("execution_id", h.getId()));
                if (arts.isEmpty()) continue;
                for (ExecutionArtifact a : arts) {
                    try {
                        // Wipe the on-disk file first (best-effort), then the row.
                        Path p = Paths.get(a.getPath());
                        Files.deleteIfExists(p);
                        artifactMapper.deleteById(a.getId());
                        deletedArtifacts++;
                    } catch (IOException ioe) {
                        log.warn("cleanup: delete artifact file failed for {}: {}",
                                a.getPath(), ioe.getMessage());
                    } catch (Exception ex) {
                        log.warn("cleanup: delete artifact row failed for {}: {}",
                                a.getId(), ex.getMessage());
                    }
                }
            }
        }

        // Execution dirs
        int eDays = executionDays();
        if (eDays > 0) {
            deletedDirs = deleteOldExecutionDirs(eDays);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("historyDeleted", deletedHistory);
        out.put("artifactsDeleted", deletedArtifacts);
        out.put("executionDirsDeleted", deletedDirs);
        out.put("skippedRunning", skippedRunning);
        out.put("failedDeleteCount", failedDeletes.size());
        if (!failedDeletes.isEmpty()) out.put("failedExecutionIds", failedDeletes);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        out.put("ranAt", Instant.now().toString());
        log.info("cleanup: deleted history={} artifacts={} dirs={} skippedRunning={} elapsedMs={}",
                deletedHistory, deletedArtifacts, deletedDirs, skippedRunning,
                System.currentTimeMillis() - start);
        return out;
    }

    /**
     * V2: daily scheduled cleanup. Spring's @Scheduled is wired by the
     * application's {@code @EnableScheduling} (added in this phase). The
     * cron expression is read from {@link ScriptBoxProperties#getCleanupCron()}.
     * Failure is logged but does not abort the schedule — the next fire
     * will retry.
     */
    @Scheduled(cron = "${scriptbox.cleanup-cron}")
    public void scheduledCleanup() {
        try {
            apply();
        } catch (Exception e) {
            log.error("scheduled cleanup failed: {}", e.getMessage(), e);
        }
    }

    private boolean isRunning(long executionId) {
        return runningRegistry.get(executionId) != null;
    }

    private int countOldExecutionDirs(int days) {
        Path root = Paths.get(props.getExecutionsDir()).toAbsolutePath();
        if (!Files.isDirectory(root)) return 0;
        long cutoffMs = System.currentTimeMillis() - (days * 86_400_000L);
        int count = 0;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(p)) continue;
                try {
                    long mtime = Files.getLastModifiedTime(p).toMillis();
                    if (mtime < cutoffMs) {
                        // Skip if any executionId in the dir name is still live.
                        Long id = parseId(p.getFileName().toString());
                        if (id != null && isRunning(id)) continue;
                        count++;
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            log.warn("countOldExecutionDirs: {}", e.getMessage());
        }
        return count;
    }

    private int deleteOldExecutionDirs(int days) {
        Path root = Paths.get(props.getExecutionsDir()).toAbsolutePath();
        if (!Files.isDirectory(root)) return 0;
        long cutoffMs = System.currentTimeMillis() - (days * 86_400_000L);
        int deleted = 0;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(p)) continue;
                try {
                    long mtime = Files.getLastModifiedTime(p).toMillis();
                    if (mtime < cutoffMs) {
                        Long id = parseId(p.getFileName().toString());
                        if (id != null && isRunning(id)) continue;
                        deleteRecursively(p);
                        deleted++;
                    }
                } catch (IOException ioe) {
                    log.warn("deleteRecursively({}): {}", p, ioe.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("deleteOldExecutionDirs: {}", e.getMessage());
        }
        return deleted;
    }

    private void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> stream = Files.walk(p)) {
            // Reverse order so files are deleted before their parent dirs.
            stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(child -> {
                        try { Files.deleteIfExists(child); } catch (IOException ignored) {}
                    });
        }
    }

    private Long parseId(String name) {
        try { return Long.parseLong(name); }
        catch (NumberFormatException e) { return null; }
    }
}