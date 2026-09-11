package com.bigdata.scriptbox.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class ExecutionRequest {
    private Long scriptId;
    private Long tenantId;
    private Map<String, String> params = new LinkedHashMap<>();
    /** Optional preset id; preset values are merged under explicit params. */
    private Long presetId;
    /** Optional uploaded file inputs: paramName -> stored absolute path on server. */
    private Map<String, String> fileInputs;
    /** Optional batch id; set by BatchService. */
    private String batchId;
    /** Optional row index within a batch. */
    private Integer batchRowIndex;
    /** Optional scenario id; set by ScenarioService. */
    private Long scenarioId;
    /** Optional step number within a scenario. */
    private Integer scenarioStepNo;

    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> params) { this.params = params; }
    public Long getPresetId() { return presetId; }
    public void setPresetId(Long presetId) { this.presetId = presetId; }
    public Map<String, String> getFileInputs() { return fileInputs; }
    public void setFileInputs(Map<String, String> fileInputs) { this.fileInputs = fileInputs; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public Integer getBatchRowIndex() { return batchRowIndex; }
    public void setBatchRowIndex(Integer batchRowIndex) { this.batchRowIndex = batchRowIndex; }
    public Long getScenarioId() { return scenarioId; }
    public void setScenarioId(Long scenarioId) { this.scenarioId = scenarioId; }
    public Integer getScenarioStepNo() { return scenarioStepNo; }
    public void setScenarioStepNo(Integer scenarioStepNo) { this.scenarioStepNo = scenarioStepNo; }
}