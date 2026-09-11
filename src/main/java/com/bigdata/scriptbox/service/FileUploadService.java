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

    @Autowired
    private StoragePathService storagePathService;

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
        Path pendingRoot = storagePathService.pendingUploadDir(token);
        storagePathService.assertInside(pendingRoot, storagePathService.pendingUploadsRoot(), "pending upload");
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
        Path execDir = storagePathService.inputDirFor(executionId);
        Files.createDirectories(execDir);
        Path executionsRoot = storagePathService.executionsRoot();
        Path uploadsRoot = storagePathService.pendingUploadsRoot();

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
            if (!srcPath.startsWith(uploadsRoot)) {
                throw new IllegalArgumentException(
                        "file input must come from the upload endpoint: " + src);
            }
            String name = srcPath.getFileName().toString();
            Path target = execDir.resolve(name);
            // 路径校验：目标必须同时落在 input dir 和 executionsRoot 之内
            storagePathService.assertInside(target, execDir, "input file");
            if (!execDir.toAbsolutePath().startsWith(executionsRoot)) {
                throw new IllegalStateException("input dir escapes executions root");
            }
            Files.copy(srcPath, target, StandardCopyOption.REPLACE_EXISTING);
            out.put(param, target.toAbsolutePath().toString());
        }
        return out;
    }

    /** Best-effort cleanup of {execId}/input/ after the execution finishes.
     *  静默忽略所有异常：清理失败不应影响主流程。*/
    public void cleanup(Long executionId) {
        try {
            Path inputDir = storagePathService.inputDirFor(executionId);
            if (Files.exists(inputDir)) {
                try (var stream = Files.list(inputDir)) {
                    stream.forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                }
                Files.deleteIfExists(inputDir);
            }
        } catch (Exception ignored) {}
    }

    /** Cleanup the pending upload root referenced by an absolute path (if any).
     *  静默忽略所有异常：清理失败不应影响主流程。*/
    public void cleanupPending(String absolutePath) {
        if (absolutePath == null) return;
        try {
            Path p = Paths.get(absolutePath).toAbsolutePath();
            Path parent = p.getParent();
            if (parent != null && parent.startsWith(storagePathService.pendingUploadsRoot())) {
                Files.deleteIfExists(p);
                try (var s = Files.list(parent)) {
                    if (s.findAny().isEmpty()) Files.deleteIfExists(parent);
                }
            }
        } catch (Exception ignored) {}
    }
}