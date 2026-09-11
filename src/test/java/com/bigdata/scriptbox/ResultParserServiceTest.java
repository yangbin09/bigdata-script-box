package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResultParserServiceTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private ScriptExecutor executor;

    private Long seedScript(String name, String body) throws IOException {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(name);
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", name + ".sh", "application/x-sh",
                body.getBytes(StandardCharsets.UTF_8)));
        return saved.getId();
    }

    private Long seedTenant() {
        Tenant t = new Tenant();
        t.setName("rp-tenant");
        t.setPrincipal("rp@EXAMPLE.COM");
        t.setEnabled(true);
        return tenantService.create(t).getId();
    }

    private ExecutionHistory runWithBody(String name, String body) throws Exception {
        Long sid = seedScript(name, body);
        Long tid = seedTenant();
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        return executor.execute(req);
    }

    @Test
    void parsesWellFormedResultJson() throws Exception {
        String body = "#!/usr/bin/env bash\necho running\n" +
                "cat > result.json <<'JSON'\n" +
                "{\"status\":\"SUCCESS\",\"message\":\"ok\",\"data\":{\"recordCount\":42}}\nJSON\n";
        ExecutionHistory h = runWithBody("rp-ok", body);
        assertTrue(h.getSuccess(), "exit=" + h.getExitCode());
        assertNotNull(h.getResultJsonPath());
        assertTrue(Files.exists(Paths.get(h.getResultJsonPath())));
        assertNotNull(h.getResultJson());
        assertTrue(h.getResultJson().contains("recordCount"));
        assertEquals("SUCCESS", h.getStatus());
    }

    @Test
    void warningStatusKeepsExecutorStatus() throws Exception {
        // The script exited 0 but claimed WARNING — we keep SUCCESS since exit code was 0.
        String body = "#!/usr/bin/env bash\ncat > result.json <<'JSON'\n" +
                "{\"status\":\"WARNING\",\"data\":{\"x\":1}}\nJSON\n";
        ExecutionHistory h = runWithBody("rp-warn", body);
        assertTrue(h.getSuccess());
        // exit-code-derived status wins; WARNING from JSON does not downgrade a successful run
        assertEquals("SUCCESS", h.getStatus());
    }

    @Test
    void invalidJsonDoesNotBreakExecution() throws Exception {
        String body = "#!/usr/bin/env bash\necho alive\n" +
                "printf '{ not valid' > result.json\n";
        ExecutionHistory h = runWithBody("rp-bad", body);
        assertTrue(h.getSuccess());
        assertNull(h.getResultJson(),
                "malformed result.json must NOT pollute history.resultJson");
    }

    @Test
    void missingResultJsonLeavesFieldsNull() throws Exception {
        // success.sh has no result.json
        Path p = Paths.get("mock-scripts").resolve("success.sh");
        ExecutionHistory h = runWithBody("rp-none", Files.readString(p, StandardCharsets.UTF_8));
        assertTrue(h.getSuccess());
        assertNull(h.getResultJson());
    }

    @Test
    void readStructuredReturnsNullWhenAbsent() {
        ExecutionHistory h = new ExecutionHistory();
        // No path set
        var s = executor.history(h.getId());
        // Nothing inserted yet, but our service can be called on a transient h
        assertNull(new com.bigdata.scriptbox.service.ResultParserService(new com.fasterxml.jackson.databind.ObjectMapper()).readStructured(h));
    }
}