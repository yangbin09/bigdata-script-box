package com.bigdata.scriptbox.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脚本执行请求 DTO。
 *
 * <p>封装前端「执行」抽屉、批量执行、场景执行、历史重跑四条路径都会用到的
 * 入参；同时也是内部 BatchService / ScenarioService 之间传递执行的载体。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@link #scriptId} — 要执行的脚本 ID；</li>
 *   <li>{@link #tenantId} — 使用的租户 ID；</li>
 *   <li>{@link #params} — 显式参数（会与 Preset 参数合并，{@code params} 优先）；</li>
 *   <li>{@link #presetId} — 可选的预设 ID；</li>
 *   <li>{@link #fileInputs} — 文件型输入，paramName → 服务端落盘路径；</li>
 *   <li>{@link #batchId} / {@link #batchRowIndex} — 由 BatchService 设置，便于按批次追踪；</li>
 *   <li>{@link #scenarioId} / {@link #scenarioStepNo} — 由 ScenarioService 设置，便于按场景追踪；</li>
 *   <li>{@link #confirmToken} — 危险脚本要求用户在前端输入 "CONFIRM" 才放行；</li>
 *   <li>{@link #bypassDangerousCheck} — 历史重跑时由 HistoricalRerunService 设置，跳过二次确认。</li>
 * </ul>
 */
public class ExecutionRequest {
    /** 脚本 ID。 */
    private Long scriptId;
    /** 租户 ID。 */
    private Long tenantId;
    /** 显式参数（key → string 值）。使用 LinkedHashMap 以保留前端传入顺序。 */
    private Map<String, String> params = new LinkedHashMap<>();
    /** 可选预设 ID；预设值会在执行前合并到 params 之下。 */
    private Long presetId;
    /** 可选文件型输入：参数名 → 服务端落盘后的绝对路径。 */
    private Map<String, String> fileInputs;
    /** 批次 ID（由 BatchService 写入）。 */
    private String batchId;
    /** 当前行在批次中的序号（0-based）。 */
    private Integer batchRowIndex;
    /** 场景 ID（由 ScenarioService 写入）。 */
    private Long scenarioId;
    /** 当前步骤在场景中的序号（1-based）。 */
    private Integer scenarioStepNo;
    /** V2: 执行危险脚本时前端必须在确认框中输入 "CONFIRM"，否则拒绝执行。
     *  dry-run 与历史重跑无需此 token。 */
    private String confirmToken;
    /** V2: 历史快照重跑时设为 true，跳过危险脚本的二次确认（原执行已经过授权）。 */
    private boolean bypassDangerousCheck;

    /** @return 脚本 ID */
    public Long getScriptId() { return scriptId; }
    /** @param scriptId 脚本 ID */
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }
    /** @return 租户 ID */
    public Long getTenantId() { return tenantId; }
    /** @param tenantId 租户 ID */
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    /** @return 参数 Map（保持插入顺序） */
    public Map<String, String> getParams() { return params; }
    /** @param params 参数 Map */
    public void setParams(Map<String, String> params) { this.params = params; }
    /** @return 预设 ID，可空 */
    public Long getPresetId() { return presetId; }
    /** @param presetId 预设 ID */
    public void setPresetId(Long presetId) { this.presetId = presetId; }
    /** @return 文件输入 Map（参数名 → 服务端路径） */
    public Map<String, String> getFileInputs() { return fileInputs; }
    /** @param fileInputs 文件输入 Map */
    public void setFileInputs(Map<String, String> fileInputs) { this.fileInputs = fileInputs; }
    /** @return 批次 ID（空表示单次执行） */
    public String getBatchId() { return batchId; }
    /** @param batchId 批次 ID */
    public void setBatchId(String batchId) { this.batchId = batchId; }
    /** @return 当前行号（批次内 0-based） */
    public Integer getBatchRowIndex() { return batchRowIndex; }
    /** @param batchRowIndex 批次内行号 */
    public void setBatchRowIndex(Integer batchRowIndex) { this.batchRowIndex = batchRowIndex; }
    /** @return 场景 ID（空表示单次执行） */
    public Long getScenarioId() { return scenarioId; }
    /** @param scenarioId 场景 ID */
    public void setScenarioId(Long scenarioId) { this.scenarioId = scenarioId; }
    /** @return 场景内步骤序号（1-based） */
    public Integer getScenarioStepNo() { return scenarioStepNo; }
    /** @param scenarioStepNo 场景内步骤序号 */
    public void setScenarioStepNo(Integer scenarioStepNo) { this.scenarioStepNo = scenarioStepNo; }
    /** @return 危险脚本确认 token */
    public String getConfirmToken() { return confirmToken; }
    /** @param confirmToken 危险脚本确认 token */
    public void setConfirmToken(String confirmToken) { this.confirmToken = confirmToken; }
    /** @return 是否绕过危险脚本确认（历史重跑场景） */
    public boolean isBypassDangerousCheck() { return bypassDangerousCheck; }
    /** @param bypassDangerousCheck 是否绕过危险脚本确认 */
    public void setBypassDangerousCheck(boolean bypassDangerousCheck) { this.bypassDangerousCheck = bypassDangerousCheck; }
}