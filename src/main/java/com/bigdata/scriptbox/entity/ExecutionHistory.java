package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 一次脚本执行的历史记录。
 *
 * <p>本表是「执行历史」页面的数据源，也是「按批次」「按场景」汇总时的依据。
 * 字段覆盖三段时间：
 * <ul>
 *   <li>V1 — 基础身份（脚本 / 租户）、状态、耗时；</li>
 *   <li>V1.5 — 批次 / 场景维度、result.json 路径与内容、规范化状态；</li>
 *   <li>V2 — 脚本 SHA-256（用于"脚本已变更"提示）、执行上下文快照（用于历史重跑）。</li>
 * </ul>
 */
@TableName("execution_history")
public class ExecutionHistory {
    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 脚本 ID。 */
    private Long scriptId;
    /** 脚本名称（执行时快照，避免脚本改名后历史丢失上下文）。 */
    private String scriptName;
    /** 租户 ID。 */
    private Long tenantId;
    /** 租户名称（执行时快照）。 */
    private String tenantName;
    /** 当时的参数 JSON。 */
    private String parametersJson;
    /** 是否成功（旧字段，保留以兼容 V1 接口）。 */
    private Boolean success;
    /** 进程退出码；null 表示尚未退出。 */
    private Integer exitCode;
    /** 是否被超时杀掉。 */
    private Boolean timeout;
    /** 耗时毫秒。 */
    private Long durationMs;
    /** stdout 文件路径。 */
    private String stdoutPath;
    /** stderr 文件路径。 */
    private String stderrPath;
    /** 执行目录（stdout / stderr / artifacts / script-body 都在其下）。 */
    private String executionDir;
    /** 开始时间。 */
    private LocalDateTime startTime;
    /** 结束时间。 */
    private LocalDateTime endTime;

    // ---- V1.5 ----
    /** 批次 ID（来自 BatchService）。 */
    private String batchId;
    /** 批次内行号。 */
    private Integer batchRowIndex;
    /** 场景 ID（来自 ScenarioService）。 */
    private Long scenarioId;
    /** 场景内步骤序号。 */
    private Integer scenarioStepNo;
    /** result.json 文件路径（脚本通过 ARTIFACT_DIR 写出后被复制 / 链接）。 */
    private String resultJsonPath;
    /** 规范化状态：SUCCESS / FAILED / TIMEOUT / PRECHECK_FAILED。 */
    private String status;
    /** result.json 内容（原始 JSON 字符串，可能为 null）。 */
    private String resultJson;
    /** V2: 启动时刻脚本正文的 SHA-256；让前端不计算也能判断"脚本已变更"。 */
    private String scriptSha256;
    /** V2: 执行上下文快照 JSON（参数 + 脚本正文 + 租户 + 风险 + 并发开关），
     *  用于"按原样重跑"，即便脚本已被编辑也不影响。 */
    private String snapshotJson;

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 脚本 ID */
    public Long getScriptId() { return scriptId; }
    /** @param scriptId 脚本 ID */
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }
    /** @return 脚本名称（执行时快照） */
    public String getScriptName() { return scriptName; }
    /** @param scriptName 脚本名称 */
    public void setScriptName(String scriptName) { this.scriptName = scriptName; }
    /** @return 租户 ID */
    public Long getTenantId() { return tenantId; }
    /** @param tenantId 租户 ID */
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    /** @return 租户名称 */
    public String getTenantName() { return tenantName; }
    /** @param tenantName 租户名称 */
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    /** @return 参数 JSON */
    public String getParametersJson() { return parametersJson; }
    /** @param parametersJson 参数 JSON */
    public void setParametersJson(String parametersJson) { this.parametersJson = parametersJson; }
    /** @return 是否成功（旧字段） */
    public Boolean getSuccess() { return success; }
    /** @param success 是否成功（旧字段） */
    public void setSuccess(Boolean success) { this.success = success; }
    /** @return 进程退出码 */
    public Integer getExitCode() { return exitCode; }
    /** @param exitCode 进程退出码 */
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    /** @return 是否超时 */
    public Boolean getTimeout() { return timeout; }
    /** @param timeout 是否超时 */
    public void setTimeout(Boolean timeout) { this.timeout = timeout; }
    /** @return 耗时毫秒 */
    public Long getDurationMs() { return durationMs; }
    /** @param durationMs 耗时毫秒 */
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    /** @return stdout 文件路径 */
    public String getStdoutPath() { return stdoutPath; }
    /** @param stdoutPath stdout 文件路径 */
    public void setStdoutPath(String stdoutPath) { this.stdoutPath = stdoutPath; }
    /** @return stderr 文件路径 */
    public String getStderrPath() { return stderrPath; }
    /** @param stderrPath stderr 文件路径 */
    public void setStderrPath(String stderrPath) { this.stderrPath = stderrPath; }
    /** @return 执行目录 */
    public String getExecutionDir() { return executionDir; }
    /** @param executionDir 执行目录 */
    public void setExecutionDir(String executionDir) { this.executionDir = executionDir; }
    /** @return 开始时间 */
    public LocalDateTime getStartTime() { return startTime; }
    /** @param startTime 开始时间 */
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    /** @return 结束时间 */
    public LocalDateTime getEndTime() { return endTime; }
    /** @param endTime 结束时间 */
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    /** @return 批次 ID */
    public String getBatchId() { return batchId; }
    /** @param batchId 批次 ID */
    public void setBatchId(String batchId) { this.batchId = batchId; }
    /** @return 批次内行号 */
    public Integer getBatchRowIndex() { return batchRowIndex; }
    /** @param batchRowIndex 批次内行号 */
    public void setBatchRowIndex(Integer batchRowIndex) { this.batchRowIndex = batchRowIndex; }
    /** @return 场景 ID */
    public Long getScenarioId() { return scenarioId; }
    /** @param scenarioId 场景 ID */
    public void setScenarioId(Long scenarioId) { this.scenarioId = scenarioId; }
    /** @return 场景内步骤序号 */
    public Integer getScenarioStepNo() { return scenarioStepNo; }
    /** @param scenarioStepNo 场景内步骤序号 */
    public void setScenarioStepNo(Integer scenarioStepNo) { this.scenarioStepNo = scenarioStepNo; }
    /** @return result.json 文件路径 */
    public String getResultJsonPath() { return resultJsonPath; }
    /** @param resultJsonPath result.json 文件路径 */
    public void setResultJsonPath(String resultJsonPath) { this.resultJsonPath = resultJsonPath; }
    /** @return 规范化状态 */
    public String getStatus() { return status; }
    /** @param status 规范化状态 */
    public void setStatus(String status) { this.status = status; }
    /** @return result.json 内容 */
    public String getResultJson() { return resultJson; }
    /** @param resultJson result.json 内容 */
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    /** @return 启动时脚本 SHA-256 */
    public String getScriptSha256() { return scriptSha256; }
    /** @param scriptSha256 启动时脚本 SHA-256 */
    public void setScriptSha256(String scriptSha256) { this.scriptSha256 = scriptSha256; }
    /** @return 执行上下文快照 JSON */
    public String getSnapshotJson() { return snapshotJson; }
    /** @param snapshotJson 执行上下文快照 JSON */
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
}