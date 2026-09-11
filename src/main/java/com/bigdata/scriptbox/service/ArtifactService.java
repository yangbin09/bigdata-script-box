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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * V2: 单次执行的产物（artifact）注册服务。
 *
 * <p>磁盘布局：
 * <pre>
 *   &lt;executionsDir&gt;/&lt;executionId&gt;/
 *      artifacts/        &lt;-- 脚本的可写工作目录（$ARTIFACT_DIR）
 *         report.csv
 *         foo.txt
 *      stdout.log
 *      stderr.log
 *      result.json
 * </pre>
 *
 * <p>{@link #scanAndRegister(ExecutionHistory)} 在一次执行成功结束（或超时 / 取消）后
 * 调用，把 {@code artifacts/} 下每一个常规文件注册进 {@code execution_artifact} 表；
 * 同一 {@code (executionId, name)} 的旧行会被先清掉，避免重跑时累积脏数据。
 *
 * <p>下载走 {@link #resolveSafe(long, String)}：先按传入的标识（主键或相对文件名）
 * 找出 artifact 行，再校验 on-disk 路径确实落在该执行的 artifacts/ 目录内；
 * 任何 {@code ..} / 绝对路径 / Windows 盘符都会被拒绝，与日志下载共用同一套防御。
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    @Autowired private ScriptBoxProperties props;
    @Autowired private ExecutionArtifactMapper artifactMapper;
    @Autowired private StoragePathService storagePathService;

    /**
     * 获取某个执行的 artifacts/ 目录绝对路径，按需创建（不存在则创建）。
     */
    public Path artifactsDirFor(long executionId) throws IOException {
        Path artifacts = storagePathService.artifactsDirFor(executionId);
        Files.createDirectories(artifacts);
        return artifacts;
    }

    /** 获取某个执行的根目录（artifacts/ 的父目录）绝对路径。 */
    public Path executionDirFor(long executionId) {
        return storagePathService.executionDirFor(executionId);
    }

    /**
     * V2: 扫描某个执行的 artifacts/ 目录并把每个常规文件写一行入库。幂等：
     * 先清掉该 execution 已有行，再从磁盘重新发现，新一轮运行不会累积历史脏行。
     *
     * <p>扫描时施加的限制：
     * <ul>
     *   <li>单文件超过 {@link ScriptBoxProperties#getMaxArtifactBytes()} 跳过；</li>
     *   <li>总文件数超过 {@link ScriptBoxProperties#getMaxArtifactFiles()} 截断；</li>
     *   <li>指向 artifacts/ 外的软链接跳过。</li>
     * </ul>
     * 越限文件只 WARN 日志告警，不影响本次执行的成功状态。
     *
     * @return 实际入库的文件数（0 表示没有任何产物可注册）
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

        // 先清掉该 execution 的旧行：每次扫描都从磁盘重新发现，避免旧行
        // 遮蔽新文件，也避免残留指向已被替换内容的行。
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
            String relName = StoragePathService.ARTIFACTS_SUBDIR + "/" + p.getFileName().toString();
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
        log.info("artifact: 扫描完成 executionId={} registered={} skipped-too-large-or-count-cap",
                h.getId(), registered);
        return registered;
    }

    /**
     * 列出某个 execution 的所有产物（按 name 升序）。
     */
    public List<ExecutionArtifact> listForExecution(long executionId) {
        return artifactMapper.selectList(new QueryWrapper<ExecutionArtifact>()
                .eq("execution_id", executionId)
                .orderByAsc("name"));
    }

    /**
     * V2: 解析一个标识符（artifact 主键或相对名）到具体的 {@link ExecutionArtifact} 行 +
     * 经过路径穿越校验的 on-disk Path。
     *
     * <p>标识符可为：
     * <ul>
     *   <li>数字 —— 当作 artifact 主键；</li>
     *   <li>其它字符串 —— 当作 name（basename 或 {@code artifacts/<file>})。</li>
     * </ul>
     * 任何包含 {@code ..}、绝对路径、Windows 盘符或解析后落在 artifacts/ 之外的对象
     * 一律返回 null 并记录 WARN 日志（异常路径）。
     */
    public ResolvedArtifact resolveSafe(long executionId, String identifier) {
        if (identifier == null || identifier.isBlank()) return null;
        // 提前拒绝明显的穿越尝试，避免触碰文件系统
        if (identifier.contains("..")) return null;

        ExecutionArtifact a = null;
        // 先按主键查
        try {
            long aid = Long.parseLong(identifier);
            a = artifactMapper.selectById(aid);
            if (a != null && !a.getExecutionId().equals(executionId)) a = null;
        } catch (NumberFormatException ignored) {
            // 不是数字，回退到按 name 查
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
                // 多个同名行（重跑可能造成）取最新的一行
                candidates.sort((x, y) -> Long.compare(
                        y.getCreatedAt() == null ? 0L
                                : y.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        x.getCreatedAt() == null ? 0L
                                : x.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
                a = candidates.get(0);
            }
        }
        if (a == null) return null;

        // 路径穿越防御：两侧都 normalize 后，on-disk 必须落在 artifacts/ 之内
        Path artifacts = storagePathService.artifactsDirFor(executionId);
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

    /**
     * 把用户传入的 artifact name 归一化为 {@code artifacts/<file>} 形态。
     * 任何包含 {@code ..}、绝对路径前缀、盘符或跨平台分隔符的输入都返回 null。
     */
    private String normaliseName(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("/") || s.startsWith("\\")) return null;
        if (s.length() >= 2 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':') return null;
        // 自动剥掉前导 "artifacts/"，让调用方两种写法都可以
        if (s.startsWith(StoragePathService.ARTIFACTS_SUBDIR + "/"))
            s = s.substring(StoragePathService.ARTIFACTS_SUBDIR.length() + 1);
        if (s.contains("..") || s.contains("/") || s.contains("\\")) return null;
        return StoragePathService.ARTIFACTS_SUBDIR + "/" + s;
    }

    /** 按文件后缀猜 MIME；未知后缀统一给 application/octet-stream。 */
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

    /** 计算文件 SHA-256，返回十六进制字符串。失败抛 IllegalStateException。 */
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

    /** 解析完成的 artifact（含 on-disk 绝对路径）。 */
    public static class ResolvedArtifact {
        public final ExecutionArtifact meta;
        public final Path onDisk;
        public ResolvedArtifact(ExecutionArtifact meta, Path onDisk) {
            this.meta = meta;
            this.onDisk = onDisk;
        }
    }

    /**
     * V2: 返回给前端展示用的 view 列表（路径安全的形态）。on-disk 绝对路径不会
     * 返回给前端，只暴露相对名 + size + sha256 + mime + createdAt。
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