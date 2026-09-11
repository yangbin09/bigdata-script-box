package com.bigdata.scriptbox.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class ExecutionRequest {
    private Long scriptId;
    private Long tenantId;
    private Map<String, String> params = new LinkedHashMap<>();

    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> params) { this.params = params; }
}