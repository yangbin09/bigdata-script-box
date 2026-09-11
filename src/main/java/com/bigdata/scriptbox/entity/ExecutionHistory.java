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
}