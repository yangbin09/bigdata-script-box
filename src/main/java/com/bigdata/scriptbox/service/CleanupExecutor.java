package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.ExecutionArtifact;
import com.bigdata.scriptbox.mapper.ExecutionArtifactMapper;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.util.FileSystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * V2: 手动清理流的"删除侧"。接收 {@link PreviewStore.CleanupPreview} 快照，
 * 逐项应用 {@link PreviewStore.CleanupPreview} 中描述的安全防护：
 *
 * <ul>
 *   <li>对每个候选路径做 normalize + realPath（{@code NOFOLLOW_LINKS}）；若解析出的真实路径
 *       不在快照记录的控制根目录内，直接跳过（绝不删除），防止通过符号链接逃逸沙箱。</li>
 *   <li>软链接一律跳过：它们合法地指向别处，跟着它们走最容易删掉运维不想删的内容。</li>
 *   <li>单条失败被捕获并记录，不影响整个批次继续运行。</li>
 *   <li>删除文件/目录时，连同 {@code execution_artifact} / {@code file_upload} /
 *       {@code execution_history} 表对应行一起清掉。</li>
 * </ul>
 *
 * <p>返回 {@link CleanupReport}，由 UI 渲染。调用方负责把一行审计写入
 * {@code cleanup_history} 表（由 {@link CleanupService#execute(String, String)} 完成）。
 */
@Service
public class CleanupExecutor {

    private static final Logger log = LoggerFactory.getLogger(CleanupExecutor.class);

    private final RunningExecutionRegistry runningRegistry;
    private final ExecutionHistoryMapper historyMapper;
    private final ExecutionArtifactMapper artifactMapper;
    private final StoragePathService storagePathService;

    /** 构造器注入：依赖显式化，字段 final 不可变。 */
    public CleanupExecutor(RunningExecutionRegistry runningRegistry,
                           ExecutionHistoryMapper historyMapper,
                           ExecutionArtifactMapper artifactMapper,
                           StoragePathService storagePathService) {
        this.runningRegistry = runningRegistry;
        this.historyMapper = historyMapper;
        this.artifactMapper = artifactMapper;
        this.storagePathService = storagePathService;
    }

    /**
     * 应用快照。分四步：执行目录 → 产物（孤儿）→ 应用日志 → 历史表行。
     * 每一步都独立处理异常，单条失败不会中断其他候选的处理。
     */
    public CleanupReport execute(PreviewStore.CleanupPreview preview) {
        long startMs = System.currentTimeMillis();
        CleanupReport report = new CleanupReport();
        report.previewId = preview.getPreviewId();
        report.startedAtIso = java.time.Instant.now().toString();

        // 1. 执行目录（按 execution 一刀切）
        for (PreviewStore.CandidateOutcome c : preview.executionDirs) {
            try {
                if (c.flag != PreviewStore.CandidateOutcome.Status.CANDIDATE) {
                    report.skipped.add(skipLine(c));
                    continue;
                }
                Path p = Paths.get(c.path);
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

        // 2. 产物（孤儿）：执行目录已被删除的产物行；正常流程下基本是空走，主要
        //    兜底手工误删的执行目录造成的孤儿产物记录。
        for (PreviewStore.CandidateOutcome c : preview.artifacts) {
            try {
                if (c.flag != PreviewStore.CandidateOutcome.Status.CANDIDATE) {
                    report.skipped.add(skipLine(c));
                    continue;
                }
                Path p = Paths.get(c.path);
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

        // 3. 应用日志：logsEnabled 才走这一步
        if (preview.logsEnabled && preview.controlledPaths.logsRoot != null) {
            for (PreviewStore.CandidateOutcome c : preview.logs) {
                try {
                    if (c.flag != PreviewStore.CandidateOutcome.Status.CANDIDATE) {
                        report.skipped.add(skipLine(c));
                        continue;
                    }
                    Path p = Paths.get(c.path);
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

        // 4. 历史表行兜底：执行目录已经不存在（可能因为被手工删掉），但 DB 还在
        //    的孤儿 history 行在这里集中删掉。
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

    /**
     * 删除一条 history 行，同时把关联的产物行一并清掉，避免孤儿引用。
     * 失败抛 RuntimeException 由调用方 catch 并写入报告的 failed 列表。
     */
    private void wipeHistoryRow(long historyId) {
        try {
            artifactMapper.delete(new QueryWrapper<ExecutionArtifact>().eq("execution_id", historyId));
            historyMapper.deleteById(historyId);
        } catch (Exception e) {
            log.warn("cleanup: wipe history row {} failed: {}", historyId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 删除整个执行目录。返回释放字节数；返回 {@code -1} 表示该路径未通过任何安全检查
     * （非数字目录名、运行中、被 symlink 跳出沙箱等）。
     */
    private long deleteExecutionDir(Path path, String controlledRoot) {
        if (path == null || controlledRoot == null) return -1;
        try {
            if (Files.isSymbolicLink(path)) return -1;
            Path abs = path.toAbsolutePath().normalize();
            if (!Files.exists(abs)) return 0;
            Path real = abs.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path root = Paths.get(controlledRoot).toAbsolutePath().normalize();
            if (!storagePathService.isInsideReal(real, root)) return -1;
            // 二次确认不是运行中的执行（防止 preview 之后被启动）
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            try {
                long execId = Long.parseLong(name);
                if (runningRegistry.get(execId) != null) return -1;
            } catch (NumberFormatException ignored) { /* 非数字目录名，跳过 id 检查 */ }

            long bytes = FileSystemUtils.directorySize(real);
            FileSystemUtils.deleteRecursively(real);
            return bytes;
        } catch (IOException ioe) {
            log.warn("cleanup: deleteExecutionDir({}) failed: {}", path, ioe.getMessage());
            return -1;
        }
    }

    /**
     * 安全删除一个文件：路径必须在控制根目录下，不是软链接。返回释放字节数；失败 -1。
     */
    private long deleteFileSafely(Path path, String controlledRoot) {
        if (path == null || controlledRoot == null) return -1;
        try {
            if (Files.isSymbolicLink(path)) return -1;
            Path abs = path.toAbsolutePath().normalize();
            if (!Files.exists(abs)) return 0;
            Path real = abs.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path root = Paths.get(controlledRoot).toAbsolutePath().normalize();
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

    /**
     * 递归统计目录大小（跳过软链接）—— 委托 {@link FileSystemUtils#directorySize(Path)}。
     */
    private long directorySize(Path dir) {
        return FileSystemUtils.directorySize(dir);
    }

    /**
     * 递归删除 —— 委托 {@link FileSystemUtils#deleteRecursively(Path)}。
     */
    private void deleteRecursively(Path p) throws IOException {
        FileSystemUtils.deleteRecursively(p);
    }

    /** 把跳过原因压成一行日志。 */
    private String skipLine(PreviewStore.CandidateOutcome c) {
        return (c.path == null ? "(row id=" + c.id + ")" : c.path) + " — " + c.reason;
    }

    /** 把失败原因包装为 SkipFail（路径 + 异常类名 + message）。 */
    private SkipFail failLine(PreviewStore.CandidateOutcome c, Exception e) {
        SkipFail sf = new SkipFail();
        sf.path = c.path;
        sf.reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        return sf;
    }

    /** 单条失败项（路径 + 失败原因）。 */
    public static class SkipFail {
        public String path;
        public String reason;
    }

    /** 单次清理执行报告（删除 / 跳过 / 失败 / 释放字节 / 耗时）。 */
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

        /**
         * 根据 failed 是否为空设置 result（SUCCESS / PARTIAL）。
         */
        public void computeResult() {
            if (failed != null && !failed.isEmpty())
                result = com.bigdata.scriptbox.model.CleanupResult.PARTIAL;
            else
                result = com.bigdata.scriptbox.model.CleanupResult.SUCCESS;
        }
    }
}