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
import com.bigdata.scriptbox.util.FileSystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * V2: 手动清理编排器。清理流程完全手动，没有任何 cron、启动触发器或
 * {@code @Scheduled}。每一次删除都必须经过以下三步：
 *
 * <ol>
 *   <li>{@link #preview(int, int, int, int)} —— 扫描并生成服务端快照，不触碰任何文件。</li>
 *   <li>运维人员确认预览快照后，调用 {@link #execute(String, String)}，
 *       传入快照的 {@code previewId} 与字面量 token {@link #CONFIRM_TOKEN}。</li>
 *   <li>{@link CleanupExecutor} 在路径校验、符号链接跳过、运行中任务跳过等防护下应用快照；
 *       执行完成后向 {@code cleanup_history} 表写一行审计记录。</li>
 * </ol>
 *
 * <p>保留天数优先从 {@link SystemSettingService} 读取（key 为 {@code cleanup.historyDays}
 * 等），未读到再回退到 {@link ScriptBoxProperties} 的默认值；{@code 0} 表示禁用该类别。
 *
 * <p>清理可触及的目录是服务端硬编码的 —— 由 {@link ScriptBoxProperties#getExecutionsDir()}
 * 和 {@link ScriptBoxProperties#getLogsDir()} 派生；前端永不发送路径，API
 * 仅接受保留天数 + previewId 确认 token。
 */
@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    /** 保留天数配置项 key（对应 {@link SystemSettingService}）。 */
    public static final String K_HISTORY = "cleanup.historyDays";
    public static final String K_ARTIFACT = "cleanup.artifactDays";
    public static final String K_EXECUTION = "cleanup.executionDays";
    public static final String K_LOG = "cleanup.logDays";

    /** 运维必须输入的确认字符串。 */
    public static final String CONFIRM_TOKEN = "CLEAN";

    /** 构造器注入：所有依赖在对象创建时就齐备，便于测试与单元测试中手工 mock。 */
    public CleanupService(ScriptBoxProperties props,
                          SystemSettingService settings,
                          ExecutionHistoryMapper historyMapper,
                          ExecutionArtifactMapper artifactMapper,
                          RunningExecutionRegistry runningRegistry,
                          PreviewStore previewStore,
                          CleanupExecutor executor,
                          CleanupHistoryService historyService,
                          StoragePathService storagePathService) {
        this.props = props;
        this.settings = settings;
        this.historyMapper = historyMapper;
        this.artifactMapper = artifactMapper;
        this.runningRegistry = runningRegistry;
        this.previewStore = previewStore;
        this.executor = executor;
        this.historyService = historyService;
        this.storagePathService = storagePathService;
    }

    private final ScriptBoxProperties props;
    private final SystemSettingService settings;
    private final ExecutionHistoryMapper historyMapper;
    private final ExecutionArtifactMapper artifactMapper;
    private final RunningExecutionRegistry runningRegistry;
    private final PreviewStore previewStore;
    private final CleanupExecutor executor;
    private final CleanupHistoryService historyService;
    private final StoragePathService storagePathService;

    /**
     * 当前生效的历史保留天数（系统设置覆盖优先）。0 表示禁用。
     */
    public int historyDays()    { return settings.getInt(K_HISTORY,    props.getRetentionHistoryDays()); }

    /**
     * 当前生效的产物文件保留天数。0 表示禁用。
     */
    public int artifactDays()   { return settings.getInt(K_ARTIFACT,   props.getRetentionArtifactDays()); }

    /**
     * 当前生效的执行目录保留天数。0 表示禁用。
     */
    public int executionDays()  { return settings.getInt(K_EXECUTION,  props.getRetentionExecutionDays()); }

    /**
     * 当前生效的应用日志保留天数。0 表示禁用（仅指应用日志，stdout/stderr 不在此列）。
     */
    public int logDays()        { return settings.getInt(K_LOG,        props.getRetentionLogDays()); }

    // ----------------------------------------------------------------------
    // 预览（Preview）：纯扫描，不删除任何东西
    // ----------------------------------------------------------------------

    /**
     * 生成一个新的清理预览快照。纯读操作 —— 不删除任何文件。
     * 返回的快照同时存入 {@link PreviewStore}，调用方拿到 {@code previewId} 即可在
     * 后续 {@link #execute(String, String)} 中定位回这一份扫描结果。
     *
     * @param hDays 历史表行保留天数（≤0 跳过）
     * @param aDays 产物文件保留天数（≤0 跳过）
     * @param eDays 执行目录保留天数（≤0 跳过）
     * @param lDays 应用日志保留天数（≤0 跳过）
     * @return 扫描结果（已写入 PreviewStore）
     */
    public CleanupPreview preview(int hDays, int aDays, int eDays, int lDays) {
        CleanupPreview p = new CleanupPreview();
        p.historyDays   = Math.max(0, hDays);
        p.artifactDays  = Math.max(0, aDays);
        p.executionDays = Math.max(0, eDays);
        p.logDays       = Math.max(0, lDays);

        ControlledPaths cp = new ControlledPaths();
        cp.executionsRoot = props.getExecutionsDir();
        cp.artifactsRoot  = cp.executionsRoot; // 产物目录在每个执行目录下，无需单独 root
        cp.logsRoot       = props.getLogsDir();
        p.controlledPaths = cp;

        // 判断日志类别是否启用：logDays > 0 且 logsRoot 配置了真实存在的目录
        boolean logsEnabled = false;
        Path logsAbs = null;
        if (lDays > 0 && cp.logsRoot != null && !cp.logsRoot.isBlank()) {
            logsAbs = storagePathService.logsRoot();
            logsEnabled = Files.isDirectory(logsAbs);
        }
        p.logsEnabled = logsEnabled;
        if (lDays > 0 && !logsEnabled) {
            p.warnings.add("Application Log 目录未配置或不存在：「" + cp.logsRoot + "」，logDays 已忽略。");
        }

        Totals t = new Totals();

        // 执行目录：以目录的 lastModifiedTime 作为判断依据
        if (eDays > 0) {
            long cutoffMs = System.currentTimeMillis() - (eDays * 86_400_000L);
            Path root = storagePathService.executionsRoot();
            if (Files.isDirectory(root)) {
                try (Stream<Path> stream = Files.list(root)) {
                    for (Path child : (Iterable<Path>) stream::iterator) {
                        // 跳过非目录 / 软链接
                        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                        if (Files.isSymbolicLink(child)) continue;
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

        // 历史表行：保留天数之外
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

        // 产物表行：所属执行已超出保留期。注意：执行目录候选一旦命中，整个目录会被
        // 整体删除，目录里的产物随之消失，因此这里只列出"孤儿"产物（执行目录已不在）。
        if (aDays > 0) {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(aDays);
            List<ExecutionHistory> oldRuns = historyMapper.selectList(
                    new QueryWrapper<ExecutionHistory>().lt("start_time", cutoff));
            for (ExecutionHistory h : oldRuns) {
                if (runningRegistry.get(h.getId()) != null) continue;
                // 跳过"执行目录已被列为候选"的行，避免双重删除
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

        // 应用日志文件：logDays > 0 时扫描 logsRoot 下的常规文件
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
        log.info("cleanup: 预览生成 previewId={} executions={} artifacts={} histories={} logs={} skippedRunning={}",
                "pending", t.executionDirCount, t.artifactCount, t.historyCount,
                t.logCount, t.skippedRunning);
        return previewStore.put(p);
    }

    /**
     * 把单个执行目录的子项构建为候选结果。如果能从目录名解析出 executionId，则尝试
     * 用 {@link ExecutionHistory} 的字段补全名称 / 租户 / 开始时间，并检查是否处于
     * 运行中（运行中的执行会被标记为 SKIPPED_RUNNING，跳过删除）。
     */
    private CandidateOutcome makeExecutionCandidate(Path child, Path root) {
        CandidateOutcome c = new CandidateOutcome();
        c.path = child.toAbsolutePath().normalize().toString();
        c.sizeBytes = directorySize(child);
        try {
            c.mtimeMs = Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException ignored) {}
        Long id = parseId(child.getFileName().toString());
        c.id = id;
        if (id != null) {
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

    /** 递归统计目录大小（不含软链接）—— 委托 {@link FileSystemUtils#directorySize(Path)}。 */
    private long directorySize(Path dir) {
        return FileSystemUtils.directorySize(dir);
    }

    /** 尝试把目录名解析为 executionId；解析失败返回 null。 */
    private Long parseId(String name) {
        try { return Long.parseLong(name); }
        catch (NumberFormatException e) { return null; }
    }

    // ----------------------------------------------------------------------
    // 执行（Execute）：根据 previewId 应用快照
    // ----------------------------------------------------------------------

    /**
     * 应用 {@code previewId} 对应的快照。
     *
     * @param previewId    preview 阶段返回的预览 ID
     * @param confirmToken 必须等于 {@link #CONFIRM_TOKEN}（字面量），其他任何字符串都抛异常
     * @return 执行报告（删除数量、跳过项、失败项、释放字节数、耗时等）
     * @throws IllegalArgumentException                token 不匹配
     * @throws PreviewStore.PreviewExpiredException    快照丢失或已过期
     */
    public CleanupExecutor.CleanupReport execute(String previewId, String confirmToken) {
        if (!CONFIRM_TOKEN.equals(confirmToken)) {
            throw new IllegalArgumentException("confirmation token mismatch");
        }
        CleanupPreview preview = previewStore.get(previewId); // 找不到或过期会抛 PreviewExpiredException
        CleanupExecutor.CleanupReport report = executor.execute(preview);
        previewStore.evict(previewId);

        // 写一行 audit 行；写入失败只警告，不影响主流程
        try {
            CleanupHistory row = new CleanupHistory();
            row.setPreviewId(previewId);
            row.setRetentionJson(String.format(
                    "{\"historyDays\":%d,\"artifactDays\":%d,\"executionDays\":%d,\"logDays\":%d}",
                    preview.historyDays, preview.artifactDays,
                    preview.executionDays, preview.logDays));
            row.setResult(report.result.name());
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

        // 汇总日志（清理生命周期关键节点）
        log.info("cleanup: 清理完成 executions={} artifacts={} logs={} histories={} skipped={} failed={} bytesFreed={} elapsedMs={}",
                report.executionDeleted, report.artifactDeleted, report.logDeleted,
                report.historyDeleted, report.skippedCount(), report.failedCount(),
                report.bytesFreed, report.elapsedMs);
        return report;
    }

    /**
     * 把失败项拼成可读的摘要信息（截断在 1800 字符以内，避免审计行过大）。
     */
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

    /**
     * 最近 N 条清理审计记录（limit 会被夹在 [1, 200] 之间）。
     */
    public List<CleanupHistory> recentHistory(int limit) {
        return historyService.listRecent(Math.max(1, Math.min(limit, 200)));
    }

    /** 仅供测试用：返回当前 Instant。 */
    public Instant nowForTests() { return Instant.now(); }
}