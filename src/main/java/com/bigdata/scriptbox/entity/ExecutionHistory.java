package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("execution_history")
public class ExecutionHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scriptId;
    private String scriptName;
    private Long tenantId;
    private String tenantName;
    private String parametersJson;
    private Boolean success;
    private Integer exitCode;
    private Boolean timeout;
    private Long durationMs;
    private String stdoutPath;
    private String stderrPath;
    private String executionDir;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    // V1.5 additions
    private String batchId;
    private Integer batchRowIndex;
    private Long scenarioId;
    private Integer scenarioStepNo;
    private String resultJsonPath;
    /** Canonical vocabulary: SUCCESS / FAILED / TIMEOUT / PRECHECK_FAILED */
    private String status;
    /** Captured result.json payload as raw JSON string (or null). */
    private String resultJson;
    // V2: SHA-256 of the script body at the moment this execution started.
    // Lets the UI detect "the script has changed since this run" without
    // hashing anything client-side.
    private String scriptSha256;
    // V2: JSON snapshot of the full execution context (params + script body
    // + tenant + riskLevel + allowConcurrent) captured at start. Enables
    // "re-run this exactly as it ran" even after the script has been edited.
    private String snapshotJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }
    public String getScriptName() { return scriptName; }
    public void setScriptName(String scriptName) { this.scriptName = scriptName; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public String getParametersJson() { return parametersJson; }
    public void setParametersJson(String parametersJson) { this.parametersJson = parametersJson; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public Boolean getTimeout() { return timeout; }
    public void setTimeout(Boolean timeout) { this.timeout = timeout; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getStdoutPath() { return stdoutPath; }
    public void setStdoutPath(String stdoutPath) { this.stdoutPath = stdoutPath; }
    public String getStderrPath() { return stderrPath; }
    public void setStderrPath(String stderrPath) { this.stderrPath = stderrPath; }
    public String getExecutionDir() { return executionDir; }
    public void setExecutionDir(String executionDir) { this.executionDir = executionDir; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public Integer getBatchRowIndex() { return batchRowIndex; }
    public void setBatchRowIndex(Integer batchRowIndex) { this.batchRowIndex = batchRowIndex; }
    public Long getScenarioId() { return scenarioId; }
    public void setScenarioId(Long scenarioId) { this.scenarioId = scenarioId; }
    public Integer getScenarioStepNo() { return scenarioStepNo; }
    public void setScenarioStepNo(Integer scenarioStepNo) { this.scenarioStepNo = scenarioStepNo; }
    public String getResultJsonPath() { return resultJsonPath; }
    public void setResultJsonPath(String resultJsonPath) { this.resultJsonPath = resultJsonPath; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getScriptSha256() { return scriptSha256; }
    public void setScriptSha256(String scriptSha256) { this.scriptSha256 = scriptSha256; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
}