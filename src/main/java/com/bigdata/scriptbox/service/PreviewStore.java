package com.bigdata.scriptbox.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V2: in-memory store of recent cleanup previews.
 *
 * <p>Each {@link CleanupPreview} is keyed by a UUID generated at preview
 * time. The user must echo the {@code previewId} back when they confirm
 * the cleanup, so we can be sure they are acting on the same scan they
 * just looked at (not on a stale view from 3 hours ago). Entries expire
 * after {@link #PREVIEW_TTL_MS}; expired lookups raise
 * {@link PreviewExpiredException} so the controller can return a clear
 * error code.
 *
 * <p>Storage is intentionally in-memory: the preview snapshot is ephemeral,
 * not a long-lived audit record (that's what {@code cleanup_history} is for).
 * After a process restart, any in-flight previews are gone — that is
 * fine, the user just clicks "preview" again.
 */
@Component
public class PreviewStore {

    public static final long PREVIEW_TTL_MS = 10 * 60 * 1000L; // 10 minutes

    private final Map<String, CleanupPreview> previews = new ConcurrentHashMap<>();

    public CleanupPreview put(CleanupPreview p) {
        if (p == null) throw new IllegalArgumentException("preview is null");
        if (p.getPreviewId() == null || p.getPreviewId().isBlank()) {
            p.setPreviewId(UUID.randomUUID().toString());
        }
        p.setCreatedAt(Instant.now());
        p.setExpiresAt(Instant.now().plusMillis(PREVIEW_TTL_MS));
        previews.put(p.getPreviewId(), p);
        return p;
    }

    /**
     * Look up a preview by id. Throws {@link PreviewExpiredException} if
     * the entry is missing or its TTL has elapsed. The lookup evicts
     * expired entries opportunistically.
     */
    public CleanupPreview get(String previewId) {
        if (previewId == null || previewId.isBlank()) {
            throw new PreviewExpiredException("preview id missing");
        }
        CleanupPreview p = previews.get(previewId);
        if (p == null) throw new PreviewExpiredException("preview not found");
        if (p.getExpiresAt() != null && p.getExpiresAt().isBefore(Instant.now())) {
            previews.remove(previewId);
            throw new PreviewExpiredException("preview expired");
        }
        return p;
    }

    public void evict(String previewId) {
        if (previewId != null) previews.remove(previewId);
    }

    public int size() {
        return previews.size();
    }

    /** Outcome of a single cleanup candidate (file / row). */
    public static class CandidateOutcome {
        public enum Status { CANDIDATE, SKIPPED_RUNNING, SKIPPED_SYMLINK, SKIPPED_ESCAPE }

        public Long id;
        public String path;
        public long sizeBytes;
        public Long mtimeMs;
        public String scriptName;
        public String tenantName;
        public String startTimeIso;
        public String status;   // ExecutionHistory status, e.g. SUCCESS / FAILED
        public Status flag = Status.CANDIDATE;
        public String reason;   // populated when flag != CANDIDATE
    }

    /** Aggregate counts for the preview summary card. */
    public static class Totals {
        public int executionDirCount;
        public int artifactCount;
        public int logCount;
        public int historyCount;
        public int skippedRunning;
        public int skippedSymlink;
        public int skippedEscape;
        public long executionBytes;
        public long artifactBytes;
        public long logBytes;
        public long totalBytes;

        public int totalFiles() { return executionDirCount + artifactCount + logCount; }
    }

    /** The absolute, server-side roots that cleanup is allowed to touch. */
    public static class ControlledPaths {
        public String executionsRoot;   // <executionsDir> absolute
        public String artifactsRoot;    // same dir; cleanup deletes the whole exec dir at once
        public String logsRoot;         // <logsDir> absolute; empty if logs disabled
    }

    /** The preview snapshot. Lives only in PreviewStore. */
    public static class CleanupPreview {
        private String previewId;
        private Instant createdAt;
        private Instant expiresAt;
        // retention
        public int historyDays;
        public int artifactDays;
        public int executionDays;
        public int logDays;
        // controlled paths (server-fixed, never trusted from caller)
        public ControlledPaths controlledPaths = new ControlledPaths();
        // candidates grouped by category
        public List<CandidateOutcome> executionDirs = new ArrayList<>();
        public List<CandidateOutcome> artifacts = new ArrayList<>();
        public List<CandidateOutcome> logs = new ArrayList<>();
        public List<CandidateOutcome> histories = new ArrayList<>();
        // aggregate
        public Totals totals = new Totals();
        // free-form warnings the operator should see (e.g. "logs dir not configured")
        public List<String> warnings = new ArrayList<>();
        // flags whether each category is enabled (e.g. logDays disabled when no logs dir)
        public boolean logsEnabled = true;

        public String getPreviewId() { return previewId; }
        public void setPreviewId(String previewId) { this.previewId = previewId; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    }

    public static class PreviewExpiredException extends RuntimeException {
        public PreviewExpiredException(String msg) { super(msg); }
    }
}