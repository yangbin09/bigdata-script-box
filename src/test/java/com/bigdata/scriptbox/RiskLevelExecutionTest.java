package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.mapper.ScriptMapper;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RiskLevelExecutionTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private ScriptExecutor executor;
    @Autowired private ScriptMapper scriptMapper;

    private Long seedScript(String name, String riskLevel, Boolean allowConcurrent) {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(name);
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        Script saved;
        try {
            saved = scriptService.create(s, new InMemoryMultipartFile(
                    "file", name + ".sh", "application/x-sh",
                    "#!/bin/bash\necho ok\n".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // The create() path persists riskLevel/allowConcurrent from the entity. Update
        // them after creation so we can test the round-trip and the executor's gate.
        saved.setRiskLevel(riskLevel);
        if (allowConcurrent != null) saved.setAllowConcurrent(allowConcurrent);
        saved.setUpdateTime(java.time.LocalDateTime.now());
        scriptMapper.updateById(saved);
        return saved.getId();
    }

    private Long seedTenant() {
        Tenant t = new Tenant();
        t.setName("risk-tenant-" + System.nanoTime());
        t.setPrincipal("rt@EXAMPLE.COM");
        t.setEnabled(true);
        return tenantService.create(t).getId();
    }

    @Test
    void dangerousScriptRejectsWithoutConfirmToken() throws Exception {
        Long sid = seedScript("dangerous-no-token", "DANGEROUS", false);
        Long tid = seedTenant();
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        req.setParams(new java.util.LinkedHashMap<>());
        Exception ex = assertThrows(IllegalArgumentException.class, () -> executor.execute(req));
        assertTrue(ex.getMessage().toLowerCase().contains("dangerous")
                || ex.getMessage().contains("CONFIRM"),
                "should reject missing CONFIRM token: " + ex.getMessage());
    }

    @Test
    void dangerousScriptAcceptsConfirmToken() throws Exception {
        Long sid = seedScript("dangerous-with-token", "DANGEROUS", false);
        Long tid = seedTenant();
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        req.setParams(new java.util.LinkedHashMap<>());
        req.setConfirmToken("CONFIRM");
        ExecutionHistory h = executor.execute(req);
        assertTrue(h.getSuccess());
    }

    @Test
    void dangerousScriptAcceptsWrongToken() {
        Long sid = seedScript("dangerous-wrong-token", "DANGEROUS", false);
        Long tid = seedTenant();
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        req.setParams(new java.util.LinkedHashMap<>());
        req.setConfirmToken("WRONG");
        assertThrows(IllegalArgumentException.class, () -> executor.execute(req));
    }

    @Test
    void readOnlyScriptDoesNotRequireConfirmToken() throws Exception {
        Long sid = seedScript("readonly-script", "READ_ONLY", false);
        Long tid = seedTenant();
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        req.setParams(new java.util.LinkedHashMap<>());
        ExecutionHistory h = executor.execute(req);
        assertTrue(h.getSuccess());
    }

    @Test
    void writeScriptDoesNotRequireConfirmToken() throws Exception {
        Long sid = seedScript("write-script", "WRITE", false);
        Long tid = seedTenant();
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        req.setParams(new java.util.LinkedHashMap<>());
        ExecutionHistory h = executor.execute(req);
        assertTrue(h.getSuccess());
    }

    @Test
    void bypassDangerousCheckSkipsConfirm() throws Exception {
        // V2: HistoricalRerunService uses bypassDangerousCheck=true to replay a
        // snapshot without nagging for CONFIRM (the original run was authorised).
        Long sid = seedScript("bypass-script", "DANGEROUS", false);
        Long tid = seedTenant();
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        req.setParams(new java.util.LinkedHashMap<>());
        req.setBypassDangerousCheck(true);
        ExecutionHistory h = executor.execute(req);
        assertTrue(h.getSuccess());
    }

    @Test
    void createDefaultsRiskLevelToReadOnly() throws Exception {
        Script s = new Script();
        s.setName("default-risk-" + System.nanoTime());
        s.setDisplayName(s.getName());
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", s.getName() + ".sh", "application/x-sh",
                "#!/bin/bash\necho ok\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("READ_ONLY", saved.getRiskLevel());
        assertEquals(Boolean.FALSE, saved.getAllowConcurrent());
    }

    @Test
    void createWithDangerousLevelPersists() throws Exception {
        Script s = new Script();
        s.setName("persist-danger-" + System.nanoTime());
        s.setDisplayName(s.getName());
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        s.setRiskLevel("DANGEROUS");
        s.setAllowConcurrent(true);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", s.getName() + ".sh", "application/x-sh",
                "#!/bin/bash\necho ok\n".getBytes(StandardCharsets.UTF_8)));
        Script roundTripped = scriptService.getById(saved.getId());
        assertEquals("DANGEROUS", roundTripped.getRiskLevel());
        assertEquals(Boolean.TRUE, roundTripped.getAllowConcurrent());
    }

    @Test
    void createWithBogusRiskLevelRejected() {
        Script s = new Script();
        s.setName("bogus-risk-" + System.nanoTime());
        s.setDisplayName(s.getName());
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        s.setRiskLevel("BOGUS");
        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> scriptService.create(s, new InMemoryMultipartFile(
                        "file", s.getName() + ".sh", "application/x-sh",
                        "#!/bin/bash\necho ok\n".getBytes(StandardCharsets.UTF_8))));
        assertTrue(ex.getMessage().toLowerCase().contains("risk level"));
    }
}
