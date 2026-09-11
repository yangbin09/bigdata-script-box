package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.service.FileUploadService;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
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

class FileParameterTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private ScriptExecutor executor;
    @Autowired private FileUploadService uploadService;
    @Autowired private ScriptBoxProperties props;

    private Long seedScript() throws IOException {
        Script s = new Script();
        s.setName("file-param-" + System.nanoTime());
        s.setDisplayName("FileParam");
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", "fp.sh", "application/x-sh",
                Files.readAllBytes(Paths.get("mock-scripts/file-input.sh"))));
        var p1 = new ScriptParam(); p1.setName("sqlFile"); p1.setType("file");
        var p2 = new ScriptParam(); p2.setName("csvFile"); p2.setType("file");
        scriptService.replaceParams(saved.getId(), List.of(p1, p2));
        return saved.getId();
    }

    private Long seedTenant() {
        Tenant t = new Tenant();
        t.setName("fp-tenant");
        t.setPrincipal("fp@EXAMPLE.COM");
        t.setEnabled(true);
        return tenantService.create(t).getId();
    }

    @Test
    void uploadAndExecute() throws Exception {
        Long sid = seedScript();
        Long tid = seedTenant();

        Map<String, Object> uploaded = uploadService.savePending(
                new InMemoryMultipartFile("file", "test.sql", "text/plain",
                        "SELECT 1;\n-- hello\n".getBytes(StandardCharsets.UTF_8)));
        String pendingPath = (String) uploaded.get("absolutePath");
        assertTrue(Files.exists(Paths.get(pendingPath)));

        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        Map<String, String> fileInputs = new LinkedHashMap<>();
        fileInputs.put("sqlFile", pendingPath);
        req.setFileInputs(fileInputs);

        ExecutionHistory h = executor.execute(req);
        assertTrue(h.getSuccess());
        Path execInputDir = Paths.get(h.getExecutionDir(), "input");
        assertTrue(Files.exists(execInputDir),
                "input dir should be created at " + execInputDir);
        assertTrue(Files.list(execInputDir).findAny().isPresent());
        // Read stdout, look for the file name
        String stdout = new String(executor.readStdout(h), StandardCharsets.UTF_8);
        assertTrue(stdout.contains("sqlFile = "));
        assertTrue(stdout.contains("test.sql"));
        assertTrue(stdout.contains("SELECT 1"));
    }

    @Test
    void rejectsNonUploadPath() throws Exception {
        Long sid = seedScript();
        Long tid = seedTenant();

        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        // Path that doesn't live under data/uploads
        Map<String, String> fileInputs = new LinkedHashMap<>();
        fileInputs.put("sqlFile", "/etc/passwd");
        req.setFileInputs(fileInputs);
        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(req));
        assertTrue(ex.getMessage().contains("upload endpoint")
                || ex.getMessage().contains("not found"));
    }

    @Test
    void rejectsBadFilename() {
        Exception ex1 = assertThrows(IllegalArgumentException.class,
                () -> uploadService.savePending(new InMemoryMultipartFile(
                        "file", "../escape.sh", "text/plain", new byte[]{1})));
        assertTrue(ex1.getMessage().contains("invalid"));
        Exception ex2 = assertThrows(IllegalArgumentException.class,
                () -> uploadService.savePending(new InMemoryMultipartFile(
                        "file", "sub/dir.sh", "text/plain", new byte[]{1})));
        assertTrue(ex2.getMessage().contains("invalid"));
    }
}