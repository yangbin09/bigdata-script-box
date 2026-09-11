package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.ExecutionArtifact;
import com.bigdata.scriptbox.mapper.ExecutionArtifactMapper;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * V2: the actual deletion half of the manual cleanup flow. Given a
 * {@link PreviewStore.CleanupPreview} snapshot, walks each candidate
 * one-by-one and applies the safety checks described in
 * {@link com.bigdata.scriptbox.service.PreviewStore.CleanupPreview}:
 *
 * <ul>
 *   <li>The candidate's path is normalized + realPath'd with
 *       {@code NOFOLLOW_LINKS}; if it falls outside the
 *       controlled root recorded in the preview, it is <b>skipped</b>
 *       (never deleted). We refuse to chase symlinks to escape the
 *       sandbox.</li>
 *   <li>Soft-links are skipped outright — they may legitimately point
 *       elsewhere, and following them is the easiest way to delete
 *       something the operator didn't intend.</li>
 *   <li>Per-item failures are caught and recorded; one bad delete
 *       does not abort the run.</li>
 *   <li>The corresponding row in {@code execution_artifact} /
 *       {@code file_upload} / {@code execution_history} is removed
 *       alongside the on-disk file.</li>
 * </ul>
 *
 * <p>Returns a {@link CleanupReport} that the UI renders after the run.
 * The caller is responsible for writing one row to {@code cleanup_history}.
 */
@Service
public class CleanupExecutor {

    private static final Logger log = LoggerFactory.getLogger(CleanupExecutor.class);

    @Autowired private RunningExecutionRegistry runningRegistry;
    @Autowired private ExecutionHistoryMapper historyMapper;
    @Autowired private ExecutionArtifactMapper artifactMapper;
    @Autowired private StoragePathService storagePathService;

    public CleanupReport execute(PreviewStore.CleanupPreview preview) {
        long startMs = System.currentTimeMillis();
        CleanupReport report = new CleanupReport();
        report.previewId = preview.getPreviewId();
        report.startedAtIso = java.time.Instant.now().toString();

        // 1. Execution dirs — one directory per execution.
        for (PreviewStore.CandidateOutcome c : preview.executionDirs) {
            try {
                if (c.flag != PreviewStore.CandidateOutcome.Status.CANDIDATE) {
                    report.skipped.add(skipLine(c));
                    continue;
                }
                Path p = java.nio.file.Paths.get(c.path);
                long freed = deleteExecutionDir(p, preview.controlledPaths.executionsRoot);
                if (freed < 0) {
                    c.flag = PreviewStore.CandidateOutcome.Status.SKIPPED_ESCAPE;
                    report.skipped.add(skipLine(c));
                    continue;
                }
                report.executionDeleted++;
                report.bytesFreed += Math.max(0, freed);
                if (c.id != null) {
                    wipeHistoryRow(c.id);
                    report.historyDeleted++;
                }
            } catch (Exception e) {
                report.failed.add(failLine(c, e));
            }
        }

        // 2. Artifacts — files inside each execution's artifacts/ dir.
        //    These are removed as part of step 1, so this loop is mostly
        //    a no-op safety net for orphans (e.g. an artifact row whose
        //    parent dir got removed out-of-band).
        for (PreviewStore.CandidateOutcome c : preview.artifacts) {
            try {
                if (c.flag != PreviewStore.CandidateOutcome.Status.CANDIDATE) {
                    report.skipped.add(skipLine(c));
                    continue;
                }
                Path p = java.nio.file.Paths.get(c.path);
                long freed = deleteFileSafely(p, preview.controlledPaths.executionsRoot);
                if (freed < 0) {
                    report.skipped.add(skipLine(c));
                    continue;
                }
                if (c.id != null) {
                    try { artifactMapper.deleteById(c.id); } catch (Exception ignored) {}
                }
                report.artifactDeleted++;
                report.bytesFreed += Math.max(0, freed);
            } catch (Exception e) {
                report.failed.add(failLine(c, e));
            }
        }

        // 3. Application logs — files under the configured logsDir.
        if (preview.logsEnabled && preview.controlledPaths.logsRoot != null) {
            for (PreviewStore.CandidateOutcome c : preview.logs) {
                try {
                    if (c.flag != PreviewStore.CandidateOutcome.Status.CANDIDATE) {
                        report.skipped.add(skipLine(c));
                        continue;
                    }
                    Path p = java.nio.file.Paths.get(c.path);
                    long freed = deleteFileSafely(p, preview.controlledPaths.logsRoot);
                    if (freed < 0) {
                        report.skipped.add(skipLine(c));
                        continue;
                    }
                    report.logDeleted++;
                    report.bytesFreed += Math.max(0, freed);
                } catch (Exception e) {
                    report.failed.add(failLine(c, e));
                }
            }
        }

        // 4. Histories whose execution dir no longer exists on disk but
        //    whose DB row is still around (preview didn't catch them
        //    above because no execution dir matched).
        for (PreviewStore.CandidateOutcome c : preview.histories) {
            try {
                if (c.flag != PreviewStore.CandidateOutcome.Status.CANDIDATE) {
                    report.skipped.add(skipLine(c));
                    continue;
                }
                if (c.id != null) {
                    wipeHistoryRow(c.id);
                    report.historyDeleted++;
                }
            } catch (Exception e) {
                report.failed.add(failLine(c, e));
            }
        }

        report.finishedAtIso = java.time.Instant.now().toString();
        report.elapsedMs = System.currentTimeMillis() - startMs;
        report.computeResult();
        return report;
    }

    private void wipeHistoryRow(long historyId) {
        try {
            // Drop child artifact rows first so we don't leave orphan references.
            artifactMapper.delete(new QueryWrapper<ExecutionArtifact>().eq("execution_id", historyId));
            historyMapper.deleteById(historyId);
        } catch (Exception e) {
            log.warn("cleanup: wipe history row {} failed: {}", historyId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Delete the whole execution dir for {@code executionId}. Returns the
     * number of bytes actually freed, or {@code -1} if the path failed
     * any safety check.
     */
    private long deleteExecutionDir(Path path, String controlledRoot) {
        if (path == null || controlledRoot == null) return -1;
        try {
            if (Files.isSymbolicLink(path)) return -1;
            Path abs = path.toAbsolutePath().normalize();
            if (!Files.exists(abs)) return 0;
            Path real = abs.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path root = java.nio.file.Paths.get(controlledRoot).toAbsolutePath().normalize();
            if (!storagePathService.isInsideReal(real, root)) return -1;
            // Re-check the parsed id is still not running (race window).
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            try {
                long execId = Long.parseLong(name);
                if (runningRegistry.get(execId) != null) return -1;
            } catch (NumberFormatException ignored) { /* not numeric — skip id check */ }

            long bytes = directorySize(real);
            deleteRecursively(real);
            return bytes;
        } catch (IOException ioe) {
            log.warn("cleanup: deleteExecutionDir({}) failed: {}", path, ioe.getMessage());
            return -1;
        }
    }

    private long deleteFileSafely(Path path, String controlledRoot) {
        if (path == null || controlledRoot == null) return -1;
        try {
            if (Files.isSymbolicLink(path)) return -1;
            Path abs = path.toAbsolutePath().normalize();
            if (!Files.exists(abs)) return 0;
            Path real = abs.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path root = java.nio.file.Paths.get(controlledRoot).toAbsolutePath().normalize();
            if (!storagePathService.isInsideReal(real, root)) return -1;
            long size = 0;
            try { size = Files.size(real); } catch (IOException ignored) {}
            Files.delete(real);
            return size;
        } catch (IOException ioe) {
            log.warn("cleanup: deleteFileSafely({}) failed: {}", path, ioe.getMessage());
            return -1;
        }
    }

    private long directorySize(Path dir) {
        long total = 0;
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                try {
                    if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) total += Files.size(p);
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return total;
    }

    private void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> stream = Files.walk(p)) {
            stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(child -> { try { Files.deleteIfExists(child); } catch (IOException ignored) {} });
        }
    }

    private String skipLine(PreviewStore.CandidateOutcome c) {
        return (c.path == null ? "(row id=" + c.id + ")" : c.path) + " — " + c.reason;
    }

    private SkipFail failLine(PreviewStore.CandidateOutcome c, Exception e) {
        SkipFail sf = new SkipFail();
        sf.path = c.path;
        sf.reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        return sf;
    }

    public static class SkipFail {
        public String path;
        public String reason;
    }

    public static class CleanupReport {
        public String previewId;
        public String startedAtIso;
        public String finishedAtIso;
        public long elapsedMs;
        public int executionDeleted;
        public int artifactDeleted;
        public int logDeleted;
        public int historyDeleted;
        public long bytesFreed;
        public com.bigdata.scriptbox.model.CleanupResult result;
        public List<String> skipped = new ArrayList<>();
        public List<SkipFail> failed = new ArrayList<>();
        public String message;

        public int skippedCount() { return skipped.size(); }
        public int failedCount()  { return failed.size(); }

        public void computeResult() {
            if (failed != null && !failed.isEmpty())
                result = com.bigdata.scriptbox.model.CleanupResult.PARTIAL;
            else
                result = com.bigdata.scriptbox.model.CleanupResult.SUCCESS;
        }
    }
}