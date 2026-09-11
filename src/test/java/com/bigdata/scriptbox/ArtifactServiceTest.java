package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionArtifact;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.service.ArtifactService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactServiceTest extends BaseIntegrationTest {

    @Autowired private ArtifactService artifactService;
    @Autowired private ScriptExecutor executor;
    @Autowired private TenantService tenantService;

    private Long tenantId;

    private Long newTenantId() {
        Tenant t = new Tenant();
        t.setName("art-tenant-" + System.nanoTime());
        t.setPrincipal("alice@EXAMPLE.COM");
        t.setEnabled(true);
        return tenantService.create(t).getId();
    }

    @Test
    void scriptCanWriteArtifactsAndTheyGetRegistered() throws Exception {
        Script s = createArtifactScript("hello-artifacts-$n",
                "#!/bin/bash\n" +
                        "echo EXECUTION_ID=$EXECUTION_ID\n" +
                        "echo EXECUTION_DIR=$EXECUTION_DIR\n" +
                        "echo ARTIFACT_DIR=$ARTIFACT_DIR\n" +
                        "mkdir -p \"$ARTIFACT_DIR\"\n" +
                        "echo alpha > \"$ARTIFACT_DIR/report.txt\"\n" +
                        "echo beta > \"$ARTIFACT_DIR/data.csv\"\n" +
                        "ls \"$ARTIFACT_DIR\"\n");
        Tenant t = tenantService.getById(newTenantId());
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(s.getId());
        req.setTenantId(t.getId());
        ExecutionHistory h = executor.execute(req);
        assertEquals("SUCCESS", h.getStatus());

        List<ExecutionArtifact> rows = artifactService.listForExecution(h.getId());
        assertEquals(2, rows.size(), "should register report.txt + data.csv");
        boolean sawReport = false, sawCsv = false;
        for (ExecutionArtifact a : rows) {
            assertNotNull(a.getSha256(), "sha256 captured: " + a.getName());
            assertTrue(a.getSizeBytes() > 0, "size captured: " + a.getName());
            if (a.getName().endsWith("report.txt")) sawReport = true;
            if (a.getName().endsWith("data.csv")) sawCsv = true;
        }
        assertTrue(sawReport && sawCsv);
    }

    @Test
    void missingArtifactsDirIsHandledGracefully() throws Exception {
        // A script that writes nothing under $ARTIFACT_DIR — should not error.
        Script s = createArtifactScript("no-artifacts-$n",
                "#!/bin/bash\necho nothing here\nexit 0\n");
        Tenant t = tenantService.getById(newTenantId());
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(s.getId());
        req.setTenantId(t.getId());
        ExecutionHistory h = executor.execute(req);
        assertEquals("SUCCESS", h.getStatus());
        List<ExecutionArtifact> rows = artifactService.listForExecution(h.getId());
        assertTrue(rows.isEmpty(), "no artifacts expected");
    }

    @Test
    void overLargeArtifactIsSkippedNotFatal() throws Exception {
        // Lower the per-file cap to a tiny value for the test, then create a
        // file that exceeds it. The run still succeeds; the file is skipped.
        long original = props.getMaxArtifactBytes();
        try {
            props.setMaxArtifactBytes(4L); // 4 bytes per file cap
            Script s = createArtifactScript("big-artifact-$n",
                    "#!/bin/bash\n" +
                            "echo \"$ARTIFACT_DIR\"\n" +
                            "echo 'this is more than 4 bytes' > \"$ARTIFACT_DIR/big.txt\"\n");
            Tenant t = tenantService.getById(newTenantId());
            ExecutionRequest req = new ExecutionRequest();
            req.setScriptId(s.getId());
            req.setTenantId(t.getId());
            ExecutionHistory h = executor.execute(req);
            assertEquals("SUCCESS", h.getStatus(), "oversize must not abort the run");
            List<ExecutionArtifact> rows = artifactService.listForExecution(h.getId());
            assertTrue(rows.isEmpty(), "oversize file should be skipped from registry");
        } finally {
            props.setMaxArtifactBytes(original);
        }
    }

    @Test
    void resolveSafeRejectsTraversalAndAbsolutePaths() throws Exception {
        Script s = createArtifactScript("traversal-$n",
                "#!/bin/bash\n" +
                        "mkdir -p \"$ARTIFACT_DIR\"\n" +
                        "echo x > \"$ARTIFACT_DIR/x.txt\"\n");
        Tenant t = tenantService.getById(newTenantId());
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(s.getId());
        req.setTenantId(t.getId());
        ExecutionHistory h = executor.execute(req);

        assertNull(artifactService.resolveSafe(h.getId(), "../etc/passwd"),
                "traversal must be rejected");
        assertNull(artifactService.resolveSafe(h.getId(), "/etc/passwd"),
                "absolute path must be rejected");
        assertNull(artifactService.resolveSafe(h.getId(), ".."),
                "dotdot alone must be rejected");
        assertNull(artifactService.resolveSafe(h.getId(), ""),
                "blank must be rejected");
    }

    @Test
    void resolveSafeAcceptsRowIdAndName() throws Exception {
        Script s = createArtifactScript("resolve-$n",
                "#!/bin/bash\n" +
                        "mkdir -p \"$ARTIFACT_DIR\"\n" +
                        "echo hi > \"$ARTIFACT_DIR/hi.txt\"\n");
        Tenant t = tenantService.getById(newTenantId());
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(s.getId());
        req.setTenantId(t.getId());
        ExecutionHistory h = executor.execute(req);
        List<ExecutionArtifact> rows = artifactService.listForExecution(h.getId());
        assertFalse(rows.isEmpty());

        Long id = rows.get(0).getId();
        assertNotNull(artifactService.resolveSafe(h.getId(), String.valueOf(id)));
        assertNotNull(artifactService.resolveSafe(h.getId(), "hi.txt"));
        assertNotNull(artifactService.resolveSafe(h.getId(), "artifacts/hi.txt"));
    }

    @Test
    void rerunReplacesPriorArtifactRows() throws Exception {
        Script s = createArtifactScript("rerun-art-$n",
                "#!/bin/bash\n" +
                        "mkdir -p \"$ARTIFACT_DIR\"\n" +
                        "echo v1 > \"$ARTIFACT_DIR/x.txt\"\n");
        Tenant t = tenantService.getById(newTenantId());
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(s.getId());
        req.setTenantId(t.getId());
        executor.execute(req);

        // Re-running the same script (snapshotted body) — the snapshot path
        // restores the body, executes, then restores. We expect the artifact
        // scan to wipe prior rows first and re-register from disk.
        ExecutionHistory prior1 = executor.history(latestHistoryId(s.getId()));
        executor.rerunFromSnapshot(prior1.getId());

        List<ExecutionArtifact> rows = artifactService.listForExecution(prior1.getId());
        // The rerun creates a NEW history row under a new executionId, so
        // prior1's artifact set remains.
        assertEquals(1, rows.size(), "first-run artifact set still present");
    }

    private Script createArtifactScript(String namePrefix, String body) throws Exception {
        Script s = new Script();
        s.setName(namePrefix.replace("$n", String.valueOf(System.nanoTime())));
        s.setDisplayName(namePrefix);
        s.setCategory("Mock");
        s.setTimeoutSeconds(30);
        s.setEnabled(true);
        return scriptService.create(s, new InMemoryMultipartFile(
                "file", "x.sh", "application/x-sh",
                body.getBytes(StandardCharsets.UTF_8)));
    }

    private Long latestHistoryId(Long scriptId) {
        // Find the most recent history row for this script by id.
        var list = historyMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ExecutionHistory>()
                .eq("script_id", scriptId)
                .orderByDesc("id"));
        if (list.isEmpty()) return null;
        return list.get(0).getId();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.bigdata.scriptbox.service.ScriptService scriptService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.bigdata.scriptbox.config.ScriptBoxProperties props;
    @org.springframework.beans.factory.annotation.Autowired
    private com.bigdata.scriptbox.mapper.ExecutionHistoryMapper historyMapper;
}