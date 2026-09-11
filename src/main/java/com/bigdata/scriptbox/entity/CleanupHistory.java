package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * V2: audit row written by {@link com.bigdata.scriptbox.service.CleanupService}
 * every time a manual cleanup executes. One row per execute call, regardless
 * of how many files / rows were deleted. Powers the 'recent cleanup history'
 * list at the bottom of Settings → Data Cleanup and acts as a paper trail
 * when the operator asks "what disappeared last Tuesday at 03:14?".
 */
@TableName("cleanup_history")
public class CleanupHistory {

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_PARTIAL = "PARTIAL";
    public static final String RESULT_FAILED  = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("preview_id")
    private String previewId;
    @TableField("retention_json")
    private String retentionJson;
    private String result;
    @TableField("execution_deleted")
    private Integer executionDeleted;
    @TableField("artifact_deleted")
    private Integer artifactDeleted;
    @TableField("log_deleted")
    private Integer logDeleted;
    @TableField("history_deleted")
    private Integer historyDeleted;
    @TableField("bytes_freed")
    private Long bytesFreed;
    @TableField("skipped_count")
    private Integer skippedCount;
    @TableField("failed_count")
    private Integer failedCount;
    private String message;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getPreviewId() { return previewId; }
    public void setPreviewId(String previewId) { this.previewId = previewId; }
    public String getRetentionJson() { return retentionJson; }
    public void setRetentionJson(String retentionJson) { this.retentionJson = retentionJson; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public Integer getExecutionDeleted() { return executionDeleted; }
    public void setExecutionDeleted(Integer v) { this.executionDeleted = v; }
    public Integer getArtifactDeleted() { return artifactDeleted; }
    public void setArtifactDeleted(Integer v) { this.artifactDeleted = v; }
    public Integer getLogDeleted() { return logDeleted; }
    public void setLogDeleted(Integer v) { this.logDeleted = v; }
    public Integer getHistoryDeleted() { return historyDeleted; }
    public void setHistoryDeleted(Integer v) { this.historyDeleted = v; }
    public Long getBytesFreed() { return bytesFreed; }
    public void setBytesFreed(Long bytesFreed) { this.bytesFreed = bytesFreed; }
    public Integer getSkippedCount() { return skippedCount; }
    public void setSkippedCount(Integer v) { this.skippedCount = v; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer v) { this.failedCount = v; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}