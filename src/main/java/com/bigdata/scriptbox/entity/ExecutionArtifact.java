package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * V2: a single file produced by a script run. The script writes under
 * {@code $ARTIFACT_DIR} (always {@code <executionsDir>/<executionId>/artifacts/});
 * after the run completes we scan that directory and persist one row per
 * regular file. Re-runs replace rows for the same {@code name} rather than
 * appending, so the table never grows stale across runs.
 */
@TableName("execution_artifact")
public class ExecutionArtifact {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("execution_id")
    private Long executionId;
    /** Relative path under the execution dir, e.g. {@code artifacts/report.csv}. */
    private String name;
    /** Absolute on-disk path. Never returned to clients verbatim. */
    private String path;
    @TableField("size_bytes")
    private Long sizeBytes;
    private String sha256;
    @TableField("mime_type")
    private String mimeType;
    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getExecutionId() { return executionId; }
    public void setExecutionId(Long executionId) { this.executionId = executionId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}