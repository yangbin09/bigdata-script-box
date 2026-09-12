package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 两步式文件参数上传流程：
 * <ol>
 *   <li>{@link #savePending(MultipartFile)} —— 把上传暂存到 {@code ./data/uploads/<uuid>/<safeName>}，
 *       返回 {@code {token, originalName, absolutePath}}；UI 把 token 带回给执行请求。</li>
 *   <li>{@link #promoteForExecution(Long, Map)} —— 执行开始时，把 pending 文件复制到
 *       {@code ./data/executions/<execId>/input/<safeName>}，返回新的绝对路径（即真正传给 shell 的路径）。</li>
 * </ol>
 *
 * <p>两个端点都会校验文件名（不允许 {@code ..}、路径分隔符）并对大小做限制。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileUploadService {

    /** 文件名合法字符集（白名单：字母 / 数字 / 点 / 下划线 / 短横）。 */
    private static final Pattern SAFE_NAME = Pattern.compile("[^A-Za-z0-9._-]");

    private final ScriptBoxProperties props;
    private final StoragePathService storagePathService;

    /**
     * 暂存一个上传文件，返回 token + 原名 + 暂存绝对路径 + 大小。
     *
     * @throws IllegalArgumentException 文件为空 / 超大 / 名称非法
     */
    public Map<String, Object> savePending(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("empty file");
        long max = props.getMaxInputFileBytes();
        if (file.getSize() > max) throw new IllegalArgumentException("file too large (max " + max + " bytes)");

        String original = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        // 先用原始名做校验：拒绝任何包含 .. 或路径分隔符的"文件名"
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
        log.info("upload: 文件暂存 token={} originalName={} size={} bytes", token, base, file.getSize());
        return out;
    }

    /**
     * 把 {@code paramName -> pendingAbsolutePath} 的入参复制到
     * {@code <executionsDir>/<execId>/input/}，返回每个参数名对应的"shell 看到"
     * 的新绝对路径。
     *
     * <p>安全约束：
     * <ul>
     *   <li>源文件必须存在；</li>
     *   <li>源路径必须落在 {@code pendingUploadsRoot} 内（防止客户端伪造路径）；</li>
     *   <li>目标路径必须同时落在 input dir 和 executionsRoot 内（{@link StoragePathService#assertInside} 校验）。</li>
     * </ul>
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
            // 源路径必须从已知安全的 uploads 根解析 —— 客户端无法诱导我们读任意路径
            if (!srcPath.startsWith(uploadsRoot)) {
                throw new IllegalArgumentException(
                        "file input must come from the upload endpoint: " + src);
            }
            String name = srcPath.getFileName().toString();
            Path target = execDir.resolve(name);
            // 路径校验：目标必须同时落在 input dir 与 executionsRoot 之内
            storagePathService.assertInside(target, execDir, "input file");
            if (!execDir.toAbsolutePath().startsWith(executionsRoot)) {
                throw new IllegalStateException("input dir escapes executions root");
            }
            Files.copy(srcPath, target, StandardCopyOption.REPLACE_EXISTING);
            out.put(param, target.toAbsolutePath().toString());
        }
        return out;
    }

    /**
     * 尽力清理 {@code <execId>/input/} 目录（执行结束后调用）。所有异常静默吞掉：
     * 清理失败不应影响主流程。
     */
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

    /**
     * 尽力清理 pending 上传目录（按绝对路径推断 token 目录）。
     * 所有异常静默吞掉。
     */
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