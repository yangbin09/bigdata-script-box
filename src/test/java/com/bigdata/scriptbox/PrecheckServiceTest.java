package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.service.PrecheckService;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrecheckServiceTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private ScriptExecutor executor;
    @Autowired private PrecheckService precheckService;

    private Long seedScript(String name, String precheckJson) throws IOException {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(name);
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        s.setPrecheckConfigJson(precheckJson);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", name + ".sh", "application/x-sh",
                "#!/bin/bash\necho hi\n".getBytes(StandardCharsets.UTF_8)));
        // create() re-inserts without precheck config; apply it again explicitly
        saved.setPrecheckConfigJson(precheckJson);
        saved.setUpdateTime(java.time.LocalDateTime.now());
        scriptService.getMapper().updateById(saved);
        return saved.getId();
    }

    private Long seedTenant(String name, boolean enabled) {
        Tenant t = new Tenant();
        t.setName(name);
        t.setPrincipal(name + "@EXAMPLE.COM");
        t.setEnabled(enabled);
        return tenantService.create(t).getId();
    }

    @Test
    void noConfigMeansSkipped() throws Exception {
        Long sid = seedScript("pc-skip", null);
        Script script = scriptService.getById(sid);
        Tenant tenant = tenantService.getById(seedTenant("pc-t1", true));
        Map<String, Object> out = precheckService.run(script, tenant);
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals(Boolean.TRUE, out.get("skipped"));
    }

    @Test
    void kerberosMissingPrincipalFails() throws Exception {
        String cfg = "{\"kerberos\":true}";
        Long sid = seedScript("pc-krb", cfg);
        Script script = scriptService.getById(sid);
        Tenant tenant = new Tenant();
        tenant.setName("pc-no-principal");
        tenant.setPrincipal(""); // empty
        tenant.setEnabled(true);
        Long tid = tenantService.create(tenant).getId();
        tenant = tenantService.getById(tid);
        Map<String, Object> out = precheckService.run(script, tenant);
        assertEquals(Boolean.FALSE, out.get("ok"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertFalse(results.isEmpty());
        assertEquals("Kerberos", results.get(0).get("name"));
        assertEquals(Boolean.FALSE, results.get(0).get("ok"));
    }

    @Test
    void commandExistsInPath() throws Exception {
        String cfg = "{\"commands\":[\"bash\"]}";
        Long sid = seedScript("pc-cmd-ok", cfg);
        Script script = scriptService.getById(sid);
        Tenant tenant = tenantService.getById(seedTenant("pc-t2", true));
        Map<String, Object> out = precheckService.run(script, tenant);
        assertEquals(Boolean.TRUE, out.get("ok"));
    }

    @Test
    void commandNotInPathFails() throws Exception {
        String cfg = "{\"commands\":[\"this-command-does-not-exist-12345\"]}";
        Long sid = seedScript("pc-cmd-bad", cfg);
        Script script = scriptService.getById(sid);
        Tenant tenant = tenantService.getById(seedTenant("pc-t3", true));
        Map<String, Object> out = precheckService.run(script, tenant);
        assertEquals(Boolean.FALSE, out.get("ok"));
    }

    @Test
    void fileExists() throws Exception {
        String cfg = "{\"files\":[\"/tmp\"]}";
        Long sid = seedScript("pc-file-ok", cfg);
        Script script = scriptService.getById(sid);
        Tenant tenant = tenantService.getById(seedTenant("pc-t4", true));
        Map<String, Object> out = precheckService.run(script, tenant);
        assertEquals(Boolean.TRUE, out.get("ok"));
    }

    @Test
    void missingFileFails() throws Exception {
        String cfg = "{\"files\":[\"/no/such/path/should/exist/12345\"]}";
        Long sid = seedScript("pc-file-bad", cfg);
        Script script = scriptService.getById(sid);
        Tenant tenant = tenantService.getById(seedTenant("pc-t5", true));
        Map<String, Object> out = precheckService.run(script, tenant);
        assertEquals(Boolean.FALSE, out.get("ok"));
    }

    @Test
    void directoryWritable() throws Exception {
        String cfg = "{\"writableDirectories\":[\"/tmp\"]}";
        Long sid = seedScript("pc-dir", cfg);
        Script script = scriptService.getById(sid);
        Tenant tenant = tenantService.getById(seedTenant("pc-t6", true));
        Map<String, Object> out = precheckService.run(script, tenant);
        assertEquals(Boolean.TRUE, out.get("ok"));
    }

    @Test
    void executeShortCircuitsOnPrecheckFailure() throws Exception {
        String cfg = "{\"commands\":[\"missing-tool-xyz\"]}";
        Long sid = seedScript("pc-exec-bad", cfg);
        Long tid = seedTenant("pc-t-exec", true);
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        ExecutionHistory h = executor.execute(req);
        assertFalse(h.getSuccess());
        assertEquals("PRECHECK_FAILED", h.getStatus());
        assertEquals(-1, h.getExitCode());
    }
}