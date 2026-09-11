package com.bigdata.scriptbox.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V2: 内存中的"清理预览"快照存储。
 *
 * <p>每个 {@link CleanupPreview} 用预览时生成的 UUID 作为 key。运维在确认清理时
 * 必须把 {@code previewId} 一起回传，这样可以保证他确认的是"刚才看到的扫描"，
 * 而不是 3 小时前看过的旧视图。条目在 {@link #PREVIEW_TTL_MS} 后过期，过期查询
 * 抛 {@link PreviewExpiredException} 让控制器返回明确错误码。
 *
 * <p>故意用内存存储：预览快照是临时态，不是长留的审计记录（那是 {@code cleanup_history}
 * 干的事）。进程重启后未确认的预览直接消失 —— 这没问题，让用户再点一次"预览"即可。
 */
@Component
public class PreviewStore {

    /** 预览有效期：10 分钟。 */
    public static final long PREVIEW_TTL_MS = 10 * 60 * 1000L;

    private final Map<String, CleanupPreview> previews = new ConcurrentHashMap<>();

    /**
     * 把一份预览存入内存，自动生成 UUID（若未提供）并设置创建 / 过期时间。
     */
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
     * 按 id 取出预览。快照不存在或已过期抛 {@link PreviewExpiredException}；
     * 同时顺手把过期条目 evict 掉。
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

    /** 主动 evict 一个预览（执行成功后调用，避免内存驻留）。 */
    public void evict(String previewId) {
        if (previewId != null) previews.remove(previewId);
    }

    /** 当前内存中的预览条数（主要给监控 / 测试用）。 */
    public int size() {
        return previews.size();
    }

    /** 单条清理候选的扫描结果。 */
    public static class CandidateOutcome {
        public enum Status { CANDIDATE, SKIPPED_RUNNING, SKIPPED_SYMLINK, SKIPPED_ESCAPE }

        public Long id;
        public String path;
        public long sizeBytes;
        public Long mtimeMs;
        public String scriptName;
        public String tenantName;
        public String startTimeIso;
        /** ExecutionHistory 的状态字段（SUCCESS / FAILED / CANCELLED 等）。 */
        public String status;
        public Status flag = Status.CANDIDATE;
        /** flag != CANDIDATE 时填入原因。 */
        public String reason;
    }

    /** 预览汇总卡片的聚合统计。 */
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

    /** 清理可触及的"绝对"根目录（服务端硬编码，绝不信任前端传入）。 */
    public static class ControlledPaths {
        /** {@code <executionsDir>} 绝对路径。 */
        public String executionsRoot;
        /** 同上，清理会按执行目录一刀切，这里冗余便于前端显示。 */
        public String artifactsRoot;
        /** {@code <logsDir>} 绝对路径；未配置应用日志清理时为空。 */
        public String logsRoot;
    }

    /** 预览快照本体。只活在 PreviewStore 里。 */
    public static class CleanupPreview {
        private String previewId;
        private Instant createdAt;
        private Instant expiresAt;
        // 保留天数（来自请求，已被夹在 [0, ...]）
        public int historyDays;
        public int artifactDays;
        public int executionDays;
        public int logDays;
        /** 控制路径（服务端硬编码）。 */
        public ControlledPaths controlledPaths = new ControlledPaths();
        /** 按类别分组的候选列表。 */
        public List<CandidateOutcome> executionDirs = new ArrayList<>();
        public List<CandidateOutcome> artifacts = new ArrayList<>();
        public List<CandidateOutcome> logs = new ArrayList<>();
        public List<CandidateOutcome> histories = new ArrayList<>();
        /** 汇总数据。 */
        public Totals totals = new Totals();
        /** 自由格式的警告文案（例如"日志目录未配置"）。 */
        public List<String> warnings = new ArrayList<>();
        /** 日志类别是否启用（logDays 启用且 logsDir 配置且存在）。 */
        public boolean logsEnabled = true;

        public String getPreviewId() { return previewId; }
        public void setPreviewId(String previewId) { this.previewId = previewId; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    }

    /** 预览快照不存在 / 过期异常。 */
    public static class PreviewExpiredException extends RuntimeException {
        public PreviewExpiredException(String msg) { super(msg); }
    }
}