package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionSnapshotTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private ScriptExecutor executor;
    @Autowired private com.bigdata.scriptbox.mapper.GlobalVariableMapper globalVariableMapper;

    private static final String BODY =
            "#!/bin/bash\n" +
            "for arg in \"$@\"; do echo \"arg=$arg\"; done\n" +
            "exit 0\n";

    private Long seedScript(String name) {
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
                    BODY.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
        ScriptParam p = new ScriptParam();
        p.setName("greeting");
        p.setType("text");
        p.setDefaultValue("hi");
        p.setRequired(false);
        p.setSortOrder(0);
        scriptService.replaceParams(saved.getId(), List.of(p));
        return saved.getId();
    }

    private Long seedTenant() {
        Tenant t = new Tenant();
        t.setName("snap-tenant-" + System.nanoTime());
        t.setPrincipal("snap@EXAMPLE.COM");
        t.setEnabled(true);
        return tenantService.create(t).getId();
    }

    @Test
    void snapshotCapturedOnNormalRun() throws Exception {
        Long sid = seedScript("snap-basic");
        Long tid = seedTenant();

        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        req.setParams(new LinkedHashMap<>(Map.of("greeting", "hello")));

        ExecutionHistory h = executor.execute(req);
        assertTrue(h.getSuccess(), "exit=" + h.getExitCode());

        // SHA-256 must be a 64-char hex (sha-256 of the body).
        assertNotNull(h.getScriptSha256(), "scriptSha256 should be captured");
        assertEquals(64, h.getScriptSha256().length(), "should be a SHA-256 hex");
        assertTrue(h.getScriptSha256().matches("[0-9a-f]{64}"),
                "should be lowercase hex: " + h.getScriptSha256());

        // snapshot must round-trip as JSON.
        assertNotNull(h.getSnapshotJson(), "snapshotJson should be captured");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> snap = mapper.readValue(h.getSnapshotJson(), Map.class);
        assertEquals(sid, ((Number) snap.get("scriptId")).longValue());
        assertEquals(tid, ((Number) snap.get("tenantId")).longValue());
        assertEquals("hello", ((Map) snap.get("params")).get("greeting"));
        assertEquals(BODY, snap.get("body"));
    }

    @Test
    void snapshotIsUniquePerRun() throws Exception {
        // Two runs with identical params still produce distinct executionIds
        // and both have their snapshot row populated.
        Long sid = seedScript("snap-twice");
        Long tid = seedTenant();

        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);

        ExecutionHistory h1 = executor.execute(req);
        ExecutionHistory h2 = executor.execute(req);
        assertNotEquals(h1.getId(), h2.getId());
        assertNotNull(h1.getSnapshotJson());
        assertNotNull(h2.getSnapshotJson());
    }

    @Test
    void snapshotChangesAfterScriptEdit() throws Exception {
        Long sid = seedScript("snap-edit");
        Long tid = seedTenant();

        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        ExecutionHistory h1 = executor.execute(req);
        String shaBefore = h1.getScriptSha256();

        // Edit the script body.
        scriptService.saveScriptBody(sid, "#!/bin/bash\necho changed\n");

        ExecutionHistory h2 = executor.execute(req);
        assertNotEquals(shaBefore, h2.getScriptSha256(),
                "SHA-256 must change after the script is edited");
    }

    @Test
    void rerunFromSnapshotExecutesOriginalBody() throws Exception {
        Long sid = seedScript("snap-rerun");
        Long tid = seedTenant();

        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        req.setParams(new LinkedHashMap<>(Map.of("greeting", "first")));
        ExecutionHistory h1 = executor.execute(req);
        assertTrue(h1.getSuccess());
        long originalSha = executor.history(h1.getId()).getScriptSha256().hashCode();
        assertTrue(originalSha != 0);

        // Edit the script so a normal re-run would see different behaviour.
        scriptService.saveScriptBody(sid, "#!/bin/bash\necho 'body has been changed'\n");

        // Now re-run from snapshot — should produce SUCCESS but the snapshot's
        // body (the original "for arg in $@; do ..."), not the edited body.
        ExecutionHistory rerun = executor.rerunFromSnapshot(h1.getId());
        assertTrue(rerun.getSuccess(), "rerun exit=" + rerun.getExitCode());
        assertEquals(originalSha,
                executor.history(rerun.getId()).getScriptSha256().hashCode(),
                "rerun must use the snapshotted SHA-256");

        // The script file on disk should be back to whatever it was before
        // the rerun (the post-edit body), not the snapshot body — otherwise
        // a successful rerun would silently corrupt the script.
        Script after = scriptService.getById(sid);
        assertEquals("#!/bin/bash\necho 'body has been changed'\n",
                scriptService.readScriptBody(sid));
        assertNotNull(after);
    }

    @Test
    void rerunFromSnapshotMissingSnapshotThrows() throws Exception {
        // Insert a real history row whose snapshotJson is null, then try
        // to rerun. The mapper is autowired at the top of the class.
        ExecutionHistory bare = new ExecutionHistory();
        bare.setId(System.currentTimeMillis() * 1000L + 999_000L);
        bare.setSnapshotJson(null);
        bare.setStatus("SUCCESS");
        bare.setSuccess(true);
        bare.setStartTime(java.time.LocalDateTime.now());
        historyMapper.insert(bare);
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> executor.rerunFromSnapshot(bare.getId()));
            assertTrue(ex.getMessage().toLowerCase().contains("snapshot"),
                    "should mention missing snapshot: " + ex.getMessage());
        } finally {
            historyMapper.deleteById(bare.getId());
        }
    }

    @Test
    void rerunFromSnapshotUnknownExecutionThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> executor.rerunFromSnapshot(987654321L));
        assertTrue(ex.getMessage().toLowerCase().contains("not found"));
    }

    @Test
    void snapshotMasksSensitiveGlobals() throws Exception {
        // Seed a sensitive global variable; the snapshot's globalVariables
        // entry for it must be masked.
        Long sid = seedScript("snap-sensitive");
        Long tid = seedTenant();
        String key = "SB_SECRET_" + System.nanoTime();
        String secret = "super-secret-value-" + System.nanoTime();
        com.bigdata.scriptbox.entity.GlobalVariable gv = new com.bigdata.scriptbox.entity.GlobalVariable();
        gv.setVariableKey(key);
        gv.setVariableValue(secret);
        gv.setSensitive(true);
        gv.setEnabled(true);
        gv.setCreateTime(java.time.LocalDateTime.now());
        gv.setUpdateTime(java.time.LocalDateTime.now());
        globalVariableMapper.insert(gv);

        try {
            ExecutionRequest req = new ExecutionRequest();
            req.setScriptId(sid);
            req.setTenantId(tid);
            ExecutionHistory h = executor.execute(req);
            assertTrue(h.getSuccess());

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> snap = mapper.readValue(h.getSnapshotJson(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, String> env = (Map<String, String>) snap.get("globalVariables");
            // The snapshot must NOT contain the raw secret. If the var made
            // it into the snapshot env (because the env builder picked it up),
            // it must be masked. Either way the raw value is forbidden.
            assertNotNull(env);
            assertEquals("******", env.get(key),
                    "sensitive variable must be masked in snapshot");
            assertNotEquals(secret, env.get(key),
                    "raw sensitive value leaked into snapshot");
            // Defensive: the raw secret must not appear anywhere in the
            // snapshot string.
            assertFalse(h.getSnapshotJson().contains(secret),
                    "raw sensitive value found verbatim in snapshot JSON");
        } finally {
            globalVariableMapper.deleteById(gv.getId());
        }
    }
}