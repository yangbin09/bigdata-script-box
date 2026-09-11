package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.CleanupHistory;
import com.bigdata.scriptbox.entity.ExecutionArtifact;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.mapper.ExecutionArtifactMapper;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.service.PreviewStore.CandidateOutcome;
import com.bigdata.scriptbox.service.PreviewStore.CleanupPreview;
import com.bigdata.scriptbox.service.PreviewStore.ControlledPaths;
import com.bigdata.scriptbox.service.PreviewStore.Totals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * V2: manual cleanup orchestrator. Cleanup is <b>fully manual</b>; there is
 * no cron, no startup trigger, no {@code @Scheduled} anywhere. Every
 * deletion must travel through:
 *
 * <ol>
 *   <li>{@link #preview(int, int, int, int)} — scan and build a server-side
 *       snapshot. No file is touched.</li>
 *   <li>Operator reviews the preview, then calls
 *       {@link #execute(String, String)} with the snapshot's
 *       {@code previewId} and the literal token {@link #CONFIRM_TOKEN}.</li>
 *   <li>{@link CleanupExecutor} applies the snapshot under
 *       path-validation, symlink-skip, running-task-skip guards. One row
 *       is written to {@code cleanup_history} when done.</li>
 * </ol>
 *
 * <p>Retention is read from {@link SystemSettingService} first (keys
 * {@code cleanup.historyDays} etc.), falling back to
 * {@link ScriptBoxProperties} defaults. {@code 0} disables a category.
 *
 * <p>The set of paths cleanup is allowed to touch is <b>server-fixed</b>
 * — derived from {@link ScriptBoxProperties#getExecutionsDir()} and
 * {@link ScriptBoxProperties#getLogsDir()}. The frontend never sends
 * paths; the API only accepts retention days + the previewId token.
 */
@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    public static final String K_HISTORY = "cleanup.historyDays";
    public static final String K_ARTIFACT = "cleanup.artifactDays";
    public static final String K_EXECUTION = "cleanup.executionDays";
    public static final String K_LOG = "cleanup.logDays";

    /** The literal string the operator must type to confirm cleanup. */
    public static final String CONFIRM_TOKEN = "CLEAN";

    @Autowired private ScriptBoxProperties props;
    @Autowired private SystemSettingService settings;
    @Autowired private ExecutionHistoryMapper historyMapper;
    @Autowired private ExecutionArtifactMapper artifactMapper;
    @Autowired private RunningExecutionRegistry runningRegistry;
    @Autowired private PreviewStore previewStore;
    @Autowired private CleanupExecutor executor;
    @Autowired private CleanupHistoryService historyService;

    /** Effective retention in days; respects override rows. 0 = disabled. */
    public int historyDays()    { return settings.getInt(K_HISTORY,    props.getRetentionHistoryDays()); }
    public int artifactDays()   { return settings.getInt(K_ARTIFACT,   props.getRetentionArtifactDays()); }
    public int executionDays()  { return settings.getInt(K_EXECUTION,  props.getRetentionExecutionDays()); }
    public int logDays()        { return settings.getInt(K_LOG,        props.getRetentionLogDays()); }

    // ----------------------------------------------------------------------
    // Preview
    // ----------------------------------------------------------------------

    /**
     * Build a fresh preview snapshot. Pure read — nothing is deleted.
     * Returns the snapshot (which is also stored in {@link PreviewStore}).
     */
    public CleanupPreview preview(int hDays, int aDays, int eDays, int lDays) {
        CleanupPreview p = new CleanupPreview();
        p.historyDays   = Math.max(0, hDays);
        p.artifactDays  = Math.max(0, aDays);
        p.executionDays = Math.max(0, eDays);
        p.logDays       = Math.max(0, lDays);

        ControlledPaths cp = new ControlledPaths();
        cp.executionsRoot = props.getExecutionsDir();
        cp.artifactsRoot  = cp.executionsRoot; // artifacts live under each execution dir
        cp.logsRoot       = props.getLogsDir();
        p.controlledPaths = cp;

        // Decide whether the log category is enabled.
        boolean logsEnabled = false;
        Path logsAbs = null;
        if (lDays > 0 && cp.logsRoot != null && !cp.logsRoot.isBlank()) {
            logsAbs = Paths.get(cp.logsRoot).toAbsolutePath().normalize();
            logsEnabled = Files.isDirectory(logsAbs);
        }
        p.logsEnabled = logsEnabled;
        if (lDays > 0 && !logsEnabled) {
            p.warnings.add("Application Log 目录未配置或不存在：「" + cp.logsRoot + "」，logDays 已忽略。");
        }

        Totals t = new Totals();

        // Execution dirs — keyed off the dir's lastModifiedTime.
        if (eDays > 0) {
            long cutoffMs = System.currentTimeMillis() - (eDays * 86_400_000L);
            Path root = Paths.get(cp.executionsRoot).toAbsolutePath().normalize();
            if (Files.isDirectory(root)) {
                try (Stream<Path> stream = Files.list(root)) {
                    for (Path child : (Iterable<Path>) stream::iterator) {
                        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                        if (Files.isSymbolicLink(child)) continue; // never follow links at preview stage
                        long mtime;
                        try { mtime = Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toMillis(); }
                        catch (IOException ioe) { continue; }
                        if (mtime >= cutoffMs) continue;
                        CandidateOutcome c = makeExecutionCandidate(child, root);
                        if (c.flag == CandidateOutcome.Status.CANDIDATE) {
                            t.executionBytes += c.sizeBytes;
                            t.executionDirCount++;
                        } else if (c.flag == CandidateOutcome.Status.SKIPPED_RUNNING) {
                            t.skippedRunning++;
                        }
                        p.executionDirs.add(c);
                    }
                } catch (IOException e) {
                    p.warnings.add("无法列出执行目录：" + e.getMessage());
                }
            }
        }

        // Histories older than historyDays.
        if (hDays > 0) {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(hDays);
            List<ExecutionHistory> rows = historyMapper.selectList(
                    new QueryWrapper<ExecutionHistory>().lt("start_time", cutoff));
            for (ExecutionHistory row : rows) {
                CandidateOutcome c = new CandidateOutcome();
                c.id = row.getId();
                c.path = "(row)";
                c.scriptName = row.getScriptName();
                c.tenantName = row.getTenantName();
                c.startTimeIso = row.getStartTime() == null ? null
                        : row.getStartTime().atZone(ZoneId.systemDefault()).toInstant().toString();
                c.status = row.getStatus();
                if (runningRegistry.get(row.getId()) != null) {
                    c.flag = CandidateOutcome.Status.SKIPPED_RUNNING;
                    c.reason = "任务正在运行";
                    t.skippedRunning++;
                } else {
                    t.historyCount++;
                }
                p.histories.add(c);
            }
        }

        // Artifacts belonging to executions older than artifactDays.
        // We piggyback on the execution-dir scan above to keep the count
        // consistent; if the dir will be removed, its artifacts go too.
        // For now, surface artifact rows only when their execution is
        // not already covered by an execution-dir candidate (e.g. orphan
        // files left behind by a manual delete).
        if (aDays > 0) {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(aDays);
            List<ExecutionHistory> oldRuns = historyMapper.selectList(
                    new QueryWrapper<ExecutionHistory>().lt("start_time", cutoff));
            for (ExecutionHistory h : oldRuns) {
                if (runningRegistry.get(h.getId()) != null) continue;
                // Skip runs whose execution dir is already a candidate —
                // they'd be removed wholesale by step 1 of execute().
                boolean covered = p.executionDirs.stream()
                        .anyMatch(c -> c.id != null && c.id.equals(h.getId())
                                && c.flag == CandidateOutcome.Status.CANDIDATE);
                if (covered) continue;
                List<ExecutionArtifact> arts = artifactMapper.selectList(
                        new QueryWrapper<ExecutionArtifact>().eq("execution_id", h.getId()));
                for (ExecutionArtifact a : arts) {
                    CandidateOutcome c = new CandidateOutcome();
                    c.id = a.getId();
                    c.path = a.getPath();
                    c.sizeBytes = a.getSizeBytes() == null ? 0 : a.getSizeBytes();
                    c.mtimeMs = a.getCreatedAt() == null ? null
                            : a.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    t.artifactBytes += c.sizeBytes;
                    t.artifactCount++;
                    p.artifacts.add(c);
                }
            }
        }

        // Application log files older than logDays.
        if (logsEnabled) {
            long cutoffMs = System.currentTimeMillis() - (lDays * 86_400_000L);
            try (Stream<Path> stream = Files.list(logsAbs)) {
                for (Path child : (Iterable<Path>) stream::iterator) {
                    if (Files.isSymbolicLink(child)) continue;
                    if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) continue;
                    long mtime;
                    try { mtime = Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toMillis(); }
                    catch (IOException ioe) { continue; }
                    if (mtime >= cutoffMs) continue;
                    CandidateOutcome c = new CandidateOutcome();
                    c.path = child.toAbsolutePath().normalize().toString();
                    try { c.sizeBytes = Files.size(child); } catch (IOException ignored) {}
                    c.mtimeMs = mtime;
                    t.logBytes += c.sizeBytes;
                    t.logCount++;
                    p.logs.add(c);
                }
            } catch (IOException ioe) {
                p.warnings.add("无法列出日志目录：" + ioe.getMessage());
            }
        }

        t.totalBytes = t.executionBytes + t.artifactBytes + t.logBytes;
        p.totals = t;
        return previewStore.put(p);
    }

    private CandidateOutcome makeExecutionCandidate(Path child, Path root) {
        CandidateOutcome c = new CandidateOutcome();
        c.path = child.toAbsolutePath().normalize().toString();
        try { c.sizeBytes = directorySize(child); } catch (IOException ignored) {}
        try {
            c.mtimeMs = Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException ignored) {}
        Long id = parseId(child.getFileName().toString());
        c.id = id;
        if (id != null) {
            // Try to enrich with script / tenant / startTime from history.
            ExecutionHistory h = historyMapper.selectById(id);
            if (h != null) {
                c.scriptName = h.getScriptName();
                c.tenantName = h.getTenantName();
                c.startTimeIso = h.getStartTime() == null ? null
                        : h.getStartTime().atZone(ZoneId.systemDefault()).toInstant().toString();
                c.status = h.getStatus();
            }
            if (runningRegistry.get(id) != null) {
                c.flag = CandidateOutcome.Status.SKIPPED_RUNNING;
                c.reason = "任务正在运行";
            }
        }
        return c;
    }

    private long directorySize(Path dir) throws IOException {
        long total = 0;
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) total += Files.size(p);
            }
        }
        return total;
    }

    private Long parseId(String name) {
        try { return Long.parseLong(name); }
        catch (NumberFormatException e) { return null; }
    }

    // ----------------------------------------------------------------------
    // Execute
    // ----------------------------------------------------------------------

    /**
     * Apply the snapshot identified by {@code previewId}. The
     * {@code confirmToken} must equal {@link #CONFIRM_TOKEN} verbatim;
     * no other string is accepted. Throws
     * {@link PreviewStore.PreviewExpiredException} if the preview is gone
     * or expired.
     */
    public CleanupExecutor.CleanupReport execute(String previewId, String confirmToken) {
        if (!CONFIRM_TOKEN.equals(confirmToken)) {
            throw new IllegalArgumentException("confirmation token mismatch");
        }
        CleanupPreview preview = previewStore.get(previewId); // throws if missing/expired
        CleanupExecutor.CleanupReport report = executor.execute(preview);
        previewStore.evict(previewId);

        // Persist audit row.
        try {
            CleanupHistory row = new CleanupHistory();
            row.setPreviewId(previewId);
            row.setRetentionJson(String.format(
                    "{\"historyDays\":%d,\"artifactDays\":%d,\"executionDays\":%d,\"logDays\":%d}",
                    preview.historyDays, preview.artifactDays,
                    preview.executionDays, preview.logDays));
            row.setResult(report.result);
            row.setExecutionDeleted(report.executionDeleted);
            row.setArtifactDeleted(report.artifactDeleted);
            row.setLogDeleted(report.logDeleted);
            row.setHistoryDeleted(report.historyDeleted);
            row.setBytesFreed(report.bytesFreed);
            row.setSkippedCount(report.skippedCount());
            row.setFailedCount(report.failedCount());
            row.setMessage(buildMessage(report));
            historyService.record(row);
        } catch (Exception e) {
            log.warn("cleanup: failed to record cleanup_history: {}", e.getMessage());
        }

        log.info("cleanup: deleted executions={} artifacts={} logs={} histories={} skipped={} failed={} bytesFreed={} elapsedMs={}",
                report.executionDeleted, report.artifactDeleted, report.logDeleted,
                report.historyDeleted, report.skippedCount(), report.failedCount(),
                report.bytesFreed, report.elapsedMs);
        return report;
    }

    private String buildMessage(CleanupExecutor.CleanupReport r) {
        if (r.failed == null || r.failed.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (CleanupExecutor.SkipFail f : r.failed) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(f.path == null ? "?" : f.path).append(": ").append(f.reason);
            if (sb.length() > 1800) { sb.append("..."); break; }
        }
        return sb.toString();
    }

    public List<CleanupHistory> recentHistory(int limit) {
        return historyService.listRecent(Math.max(1, Math.min(limit, 200)));
    }

    /** Convenience for the controller layer; just the persistence leg. */
    public Instant nowForTests() { return Instant.now(); }
}