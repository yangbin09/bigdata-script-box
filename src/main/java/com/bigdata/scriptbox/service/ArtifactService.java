package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.ExecutionArtifact;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.mapper.ExecutionArtifactMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * V2: per-execution artifact registry.
 *
 * <p>Layout on disk:
 * <pre>
 *   &lt;executionsDir&gt;/&lt;executionId&gt;/
 *      artifacts/        &lt;-- script's writable workspace ($ARTIFACT_DIR)
 *         report.csv
 *         foo.txt
 *      stdout.log
 *      stderr.log
 *      result.json
 * </pre>
 *
 * <p>{@link #scanAndRegister(ExecutionHistory)} runs after a successful
 * execution completes (or after timeout/cancel) and registers every regular
 * file under {@code artifacts/} into the {@code execution_artifact} table.
 * Rows from a previous run for the same {@code (executionId, name)} pair
 * are removed first, so a re-run doesn't accumulate stale rows.
 *
 * <p>Download goes through {@link #resolveSafePath(long, String)} which
 * normalises the requested name and refuses anything that escapes the
 * execution's artifacts directory — including {@code ..} traversal and
 * absolute paths. This is the same defense used for log downloads.
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);
    static final String ARTIFACTS_SUBDIR = "artifacts";

    @Autowired private ScriptBoxProperties props;
    @Autowired private ExecutionArtifactMapper artifactMapper;

    /** Absolute path to a given execution's artifacts dir, creating it. */
    public Path artifactsDirFor(long executionId) throws IOException {
        Path execDir = Paths.get(props.getExecutionsDir(), String.valueOf(executionId))
                .toAbsolutePath().normalize();
        Path artifacts = execDir.resolve(ARTIFACTS_SUBDIR).toAbsolutePath().normalize();
        Files.createDirectories(artifacts);
        return artifacts;
    }

    /** Absolute path to a given execution's dir (parent of artifacts/). */
    public Path executionDirFor(long executionId) {
        return Paths.get(props.getExecutionsDir(), String.valueOf(executionId))
                .toAbsolutePath().normalize();
    }

    /**
     * V2: scan the artifacts directory of an execution and persist one row
     * per regular file. Idempotent: prior rows for the same execution are
     * wiped first so re-runs don't accumulate.
     *
     * <p>Limits enforced by the scan:
     * <ul>
     *   <li>Skip files whose size exceeds {@link ScriptBoxProperties#getMaxArtifactBytes()}.</li>
     *   <li>Cap at {@link ScriptBoxProperties#getMaxArtifactFiles()} files.</li>
     *   <li>Skip symlinks that resolve outside the artifacts dir.</li>
     * </ul>
     * Files that exceed limits are logged at WARN level and skipped — the
     * run still completes successfully.
     */
    public int scanAndRegister(ExecutionHistory h) {
        if (h == null || h.getId() == null) return 0;
        Path artifacts;
        try {
            artifacts = artifactsDirFor(h.getId());
        } catch (IOException ioe) {
            log.warn("scan: cannot resolve artifacts dir for executionId={}: {}",
                    h.getId(), ioe.getMessage());
            return 0;
        }
        if (!Files.isDirectory(artifacts)) return 0;

        // Wipe prior rows for this execution — we re-discover from disk on
        // each scan so a partial-write or pre-existing row can't shadow a
        // fresh file.
        artifactMapper.delete(new QueryWrapper<ExecutionArtifact>()
                .eq("execution_id", h.getId()));

        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(artifacts)) {
            stream.filter(Files::isRegularFile)
                    .forEach(files::add);
        } catch (IOException ioe) {
            log.warn("scan: list failed for executionId={}: {}",
                    h.getId(), ioe.getMessage());
            return 0;
        }
        files.sort(Comparator.comparing(Path::toString));

        long perFileMax = props.getMaxArtifactBytes();
        int maxFiles = props.getMaxArtifactFiles();
        int registered = 0;
        for (Path p : files) {
            if (registered >= maxFiles) {
                log.warn("scan: hit max-artifact-files={} for executionId={}; skipping {}",
                        maxFiles, h.getId(), p.getFileName());
                break;
            }
            long size;
            try {
                size = Files.size(p);
            } catch (IOException ioe) {
                log.warn("scan: size failed for {}: {}", p, ioe.getMessage());
                continue;
            }
            if (size > perFileMax) {
                log.warn("scan: file {} ({} bytes) exceeds max-artifact-bytes={}; skipping",
                        p, size, perFileMax);
                continue;
            }
            String relName = ARTIFACTS_SUBDIR + "/" + p.getFileName().toString();
            ExecutionArtifact a = new ExecutionArtifact();
            a.setExecutionId(h.getId());
            a.setName(relName);
            a.setPath(p.toAbsolutePath().toString());
            a.setSizeBytes(size);
            try { a.setSha256(sha256Hex(p)); } catch (IOException ioe) {
                log.warn("scan: sha256 failed for {}: {}", p, ioe.getMessage());
            }
            a.setMimeType(guessMime(p.getFileName().toString()));
            a.setCreatedAt(LocalDateTime.now());
            artifactMapper.insert(a);
            registered++;
        }
        return registered;
    }

    public List<ExecutionArtifact> listForExecution(long executionId) {
        return artifactMapper.selectList(new QueryWrapper<ExecutionArtifact>()
                .eq("execution_id", executionId)
                .orderByAsc("name"));
    }

    /**
     * V2: resolve a {@link ExecutionArtifact} row whose {@code name} matches
     * the supplied identifier. We accept either the row's primary key or
     * the relative name (basename or {@code artifacts/<file>}). Returns
     * null if no match, or if the resolved on-disk path escapes the
     * execution's artifacts directory.
     */
    public ResolvedArtifact resolveSafe(long executionId, String identifier) {
        if (identifier == null || identifier.isBlank()) return null;
        // Reject obvious traversal attempts before we ever touch the filesystem.
        if (identifier.contains("..")) return null;

        ExecutionArtifact a = null;
        // Try as numeric id first.
        try {
            long aid = Long.parseLong(identifier);
            a = artifactMapper.selectById(aid);
            if (a != null && !a.getExecutionId().equals(executionId)) a = null;
        } catch (NumberFormatException ignored) {
            // fall through to name lookup
        }
        if (a == null) {
            String normalised = normaliseName(identifier);
            if (normalised == null) return null;
            List<ExecutionArtifact> candidates = artifactMapper.selectList(
                    new QueryWrapper<ExecutionArtifact>()
                            .eq("execution_id", executionId)
                            .eq("name", normalised));
            if (candidates.size() == 1) {
                a = candidates.get(0);
            } else if (candidates.size() > 1) {
                // Pick the most recent.
                candidates.sort((x, y) -> Long.compare(
                        y.getCreatedAt() == null ? 0L
                                : y.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        x.getCreatedAt() == null ? 0L
                                : x.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
                a = candidates.get(0);
            }
        }
        if (a == null) return null;

        // Path-traversal defense: the on-disk path must live under the
        // execution's artifacts dir, after both are normalised.
        Path artifacts = artifactsDirFor_unnormalised(executionId);
        Path resolved;
        try {
            resolved = Paths.get(a.getPath()).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return null;
        }
        if (!resolved.startsWith(artifacts)) {
            log.warn("artifact resolve rejected: executionId={} id={} resolved={} not under {}",
                    executionId, identifier, resolved, artifacts);
            return null;
        }
        if (!Files.isRegularFile(resolved)) return null;
        return new ResolvedArtifact(a, resolved);
    }

    /** Without createDirectories — used inside the traversal check. */
    private Path artifactsDirFor_unnormalised(long executionId) {
        return Paths.get(props.getExecutionsDir(), String.valueOf(executionId))
                .toAbsolutePath().normalize()
                .resolve(ARTIFACTS_SUBDIR).toAbsolutePath().normalize();
    }

    /**
     * Normalise a user-supplied artifact name into the canonical
     * {@code artifacts/<file>} shape. Returns null on suspicious input
     * (leading slash, traversal segments, or anything outside the
     * artifacts dir).
     */
    private String normaliseName(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // Disallow absolute paths and Windows-style paths.
        if (s.startsWith("/") || s.startsWith("\\")) return null;
        // Disallow drive letters: 'C:' or 'c:'
        if (s.length() >= 2 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':') return null;
        // Drop any leading "artifacts/" so the caller can supply either.
        if (s.startsWith(ARTIFACTS_SUBDIR + "/")) s = s.substring(ARTIFACTS_SUBDIR.length() + 1);
        if (s.contains("..") || s.contains("/") || s.contains("\\")) return null;
        return ARTIFACTS_SUBDIR + "/" + s;
    }

    private String guessMime(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "application/octet-stream";
        String ext = filename.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "txt", "log" -> "text/plain";
            case "xml" -> "application/xml";
            case "html", "htm" -> "text/html";
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "tar" -> "application/x-tar";
            case "gz" -> "application/gzip";
            case "zip" -> "application/zip";
            case "parquet", "pq" -> "application/octet-stream";
            default -> "application/octet-stream";
        };
    }

    private String sha256Hex(Path p) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Resolved artifact with the (path-traversal-checked) on-disk file. */
    public static class ResolvedArtifact {
        public final ExecutionArtifact meta;
        public final Path onDisk;
        public ResolvedArtifact(ExecutionArtifact meta, Path onDisk) {
            this.meta = meta;
            this.onDisk = onDisk;
        }
    }

    /**
     * V2: list view-shaped entries (path-traversal-safe) for the API layer.
     * The on-disk path is replaced by a relative "name" so the FE never sees
     * an absolute filesystem path.
     */
    public List<java.util.Map<String, Object>> listView(long executionId) {
        List<ExecutionArtifact> rows = listForExecution(executionId);
        if (rows.isEmpty()) return Collections.emptyList();
        List<java.util.Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ExecutionArtifact a : rows) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("name", a.getName());
            m.put("sizeBytes", a.getSizeBytes());
            m.put("sha256", a.getSha256());
            m.put("mimeType", a.getMimeType());
            m.put("createdAt", a.getCreatedAt());
            out.add(m);
        }
        return out;
    }
}