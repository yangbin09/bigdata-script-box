package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Two-step file parameter flow:
 *  1. savePending — stash the upload under ./data/uploads/{uuid}/{safeName}
 *     Returns {token, originalName, absolutePath}. UI shows the token back.
 *  2. promoteForExecution — when the execution starts, copy pending files
 *     into ./data/executions/{execId}/input/{safeName} and return the new
 *     absolute paths. These are the paths we hand to the shell.
 *
 * Both endpoints validate filename (no `..`, no path separators) and cap size.
 */
@Service
public class FileUploadService {

    private static final Pattern SAFE_NAME = Pattern.compile("[^A-Za-z0-9._-]");

    @Autowired
    private ScriptBoxProperties props;

    public Map<String, Object> savePending(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("empty file");
        long max = props.getMaxInputFileBytes();
        if (file.getSize() > max) throw new IllegalArgumentException("file too large (max " + max + " bytes)");

        String original = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        // Validate first using the raw original so we can reject path components
        if (original.contains("..") || original.contains("/") || original.contains("\\")) {
            throw new IllegalArgumentException("invalid filename: " + original);
        }
        String base = Paths.get(original).getFileName() == null
                ? "upload.bin"
                : Paths.get(original).getFileName().toString();
        String safe = SAFE_NAME.matcher(base).replaceAll("_");
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) safe = "upload.bin";
        if (safe.length() > 96) safe = safe.substring(safe.length() - 96);

        String token = UUID.randomUUID().toString();
        Path pendingRoot = Paths.get(props.getDataDir(), "uploads", token).toAbsolutePath();
        Files.createDirectories(pendingRoot);
        Path target = pendingRoot.resolve(safe);
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("originalName", base);
        out.put("absolutePath", target.toString());
        out.put("size", file.getSize());
        return out;
    }

    /**
     * Resolve a list of {paramName -> pendingAbsolutePath} into absolute paths
     * under {executionsDir}/{execId}/input/. Each file is copied, and the
     * returned map maps paramName -> newAbsolutePath (the path the script sees).
     */
    public Map<String, String> promoteForExecution(Long executionId, Map<String, String> pendingByParam) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (pendingByParam == null || pendingByParam.isEmpty()) return out;
        Path execDir = Paths.get(props.getExecutionsDir(), String.valueOf(executionId), "input");
        Files.createDirectories(execDir);
        Path executionsRoot = Paths.get(props.getExecutionsDir()).toAbsolutePath();

        for (Map.Entry<String, String> e : pendingByParam.entrySet()) {
            String param = e.getKey();
            String src = e.getValue();
            if (src == null || src.isBlank()) continue;
            Path srcPath;
            try { srcPath = Paths.get(src).toAbsolutePath(); }
            catch (Exception ex) { throw new IllegalArgumentException("invalid file input for " + param); }
            if (!Files.exists(srcPath)) throw new IllegalArgumentException("uploaded file not found: " + src);
            // Resolve the file from a known-safe root: only accept files under
            // {dataDir}/uploads/ — clients cannot force us to read arbitrary paths.
            Path uploadsRoot = Paths.get(props.getDataDir(), "uploads").toAbsolutePath();
            if (!srcPath.startsWith(uploadsRoot)) {
                throw new IllegalArgumentException(
                        "file input must come from the upload endpoint: " + src);
            }
            String name = srcPath.getFileName().toString();
            Path target = execDir.resolve(name);
            if (!target.toAbsolutePath().startsWith(execDir.toAbsolutePath())
                    || !execDir.toAbsolutePath().startsWith(executionsRoot)) {
                throw new IllegalStateException("resolved file path escapes execution dir");
            }
            Files.copy(srcPath, target, StandardCopyOption.REPLACE_EXISTING);
            out.put(param, target.toAbsolutePath().toString());
        }
        return out;
    }

    /** Best-effort cleanup of {execId}/input/ after the execution finishes. */
    public void cleanup(Long executionId) {
        try {
            Path inputDir = Paths.get(props.getExecutionsDir(), String.valueOf(executionId), "input");
            if (Files.exists(inputDir)) {
                try (var stream = Files.list(inputDir)) {
                    stream.forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                }
                Files.deleteIfExists(inputDir);
            }
        } catch (Exception ignored) {}
    }

    /** Cleanup the pending upload root referenced by an absolute path (if any). */
    public void cleanupPending(String absolutePath) {
        if (absolutePath == null) return;
        try {
            Path p = Paths.get(absolutePath).toAbsolutePath();
            Path parent = p.getParent();
            if (parent != null && parent.startsWith(Paths.get(props.getDataDir(), "uploads").toAbsolutePath())) {
                Files.deleteIfExists(p);
                try (var s = Files.list(parent)) {
                    if (s.findAny().isEmpty()) Files.deleteIfExists(parent);
                }
            }
        } catch (Exception ignored) {}
    }
}