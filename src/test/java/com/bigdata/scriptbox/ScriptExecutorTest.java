package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScriptExecutorTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private ScriptExecutor executor;
    @Autowired private ScriptBoxProperties props;

    private Long scriptId;
    private Long tenantId;

    @BeforeEach
    void seed() throws IOException {
        // register tenant
        Tenant t = new Tenant();
        t.setName("mock");
        t.setPrincipal("mock@EXAMPLE.COM");
        t.setEnabled(true);
        tenantId = tenantService.create(t).getId();

        scriptId = registerScript("s-success", loadMock("success.sh"), "Mock", 60, true);
        registerScript("s-failed",  loadMock("failed.sh"),       "Mock", 60, true);
        registerScript("s-stderr",  loadMock("stderr.sh"),       "Mock", 60, true);
        registerScript("s-timeout", loadMock("timeout.sh"),      "Mock", 2,  true);
        registerScript("s-large",   loadMock("large-output.sh"), "Mock", 120, true);
    }

    private Path loadMock(String fileName) {
        // Resolve against the project working directory, not the classpath.
        return Paths.get("mock-scripts").resolve(fileName);
    }

    private Long registerScript(String name, Path source, String category, int timeout, boolean enabled) throws IOException {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(name);
        s.setCategory(category);
        s.setTimeoutSeconds(timeout);
        s.setEnabled(enabled);
        byte[] body = Files.readAllBytes(source);
        // Use create() so that script path lives under scriptsDir/{id}/script.sh, satisfying
        // the executor's path-prefix check.
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", name + ".sh", "application/x-sh", body));
        return saved.getId();
    }

    private Long registerScript(String name, String category, int timeout, boolean enabled, ScriptParam... params) throws IOException {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(name);
        s.setCategory(category);
        s.setTimeoutSeconds(timeout);
        s.setEnabled(enabled);
        // We don't go through ScriptService.create() for these because we need to inject
        // the script body directly. Instead we register via create() with a body, then
        // rewrite the script path to point at a shared fixture under scriptsDir.
        byte[] placeholder = "#!/bin/bash\necho placeholder\n".getBytes(StandardCharsets.UTF_8);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", name + ".sh", "application/x-sh", placeholder));
        return saved.getId();
    }

    @Test
    void executesSuccessScript() throws Exception {
        ExecutionRequest req = baseReq(scriptId);
        ExecutionHistory h = executor.execute(req);
        assertTrue(h.getSuccess(), "exit=" + h.getExitCode());
        assertEquals(0, h.getExitCode());
        assertNotNull(h.getStdoutPath());
        byte[] out = executor.readStdout(h);
        String text = new String(out, StandardCharsets.UTF_8);
        assertTrue(text.contains("[success]"));
    }

    @Test
    void recordsFailedScript() throws Exception {
        ExecutionRequest req = baseReq(findIdByName("s-failed"));
        ExecutionHistory h = executor.execute(req);
        assertFalse(h.getSuccess());
        assertEquals(1, h.getExitCode());
        byte[] err = executor.readStderr(h);
        assertTrue(new String(err, StandardCharsets.UTF_8).contains("ERROR"));
    }

    @Test
    void capturesStderrSeparately() throws Exception {
        ExecutionRequest req = baseReq(findIdByName("s-stderr"));
        req.getParams().put("message", "hi");
        ExecutionHistory h = executor.execute(req);
        assertTrue(h.getSuccess());
        String out = new String(executor.readStdout(h), StandardCharsets.UTF_8);
        String err = new String(executor.readStderr(h), StandardCharsets.UTF_8);
        assertTrue(out.contains("[stdout]"));
        assertTrue(err.contains("[stderr]"));
    }

    @Test
    void honoursTimeout() throws Exception {
        ExecutionRequest req = baseReq(findIdByName("s-timeout"));
        ExecutionHistory h = executor.execute(req);
        assertTrue(h.getTimeout());
        assertFalse(h.getSuccess());
        // exit code will be -1 because destroyForcibly was called.
        assertEquals(-1, h.getExitCode());
    }

    @Test
    void handlesLargeOutputWithoutDeadlock() throws Exception {
        ExecutionRequest req = baseReq(findIdByName("s-large"));
        req.getParams().put("lines", "2000");
        ExecutionHistory h = executor.execute(req);
        assertTrue(h.getSuccess());
        String out = new String(executor.readStdout(h), StandardCharsets.UTF_8);
        assertTrue(out.lines().count() >= 2000);
    }

    private ExecutionRequest baseReq(Long sid) {
        ExecutionRequest r = new ExecutionRequest();
        r.setScriptId(sid);
        r.setTenantId(tenantId);
        r.setParams(new LinkedHashMap<>());
        return r;
    }

    private Long findIdByName(String name) {
        return scriptService.listAll().stream()
                .filter(s -> name.equals(s.getName())).findFirst().orElseThrow().getId();
    }
}