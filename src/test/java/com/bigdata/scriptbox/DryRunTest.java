package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.service.GlobalVariableService;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DryRunTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private ScriptExecutor executor;
    @Autowired private GlobalVariableService variableService;
    @Autowired private ScriptBoxProperties props;

    private Long seedScript() throws IOException {
        Script s = new Script();
        s.setName("preview-" + System.nanoTime());
        s.setDisplayName("Preview");
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", "p.sh", "application/x-sh",
                "#!/bin/bash\necho hello\n".getBytes(StandardCharsets.UTF_8)));
        // Add a couple of params
        var p1 = new com.bigdata.scriptbox.entity.ScriptParam();
        p1.setName("database"); p1.setType("text"); p1.setDefaultValue("def");
        var p2 = new com.bigdata.scriptbox.entity.ScriptParam();
        p2.setName("count"); p2.setType("number"); p2.setDefaultValue("10");
        scriptService.replaceParams(saved.getId(), List.of(p1, p2));
        return saved.getId();
    }

    private Long seedTenant(String name) {
        Tenant t = new Tenant();
        t.setName(name);
        t.setPrincipal(name + "@EXAMPLE.COM");
        t.setEnabled(true);
        return tenantService.create(t).getId();
    }

    @Test
    void previewDoesNotActuallyExecute() throws Exception {
        Long sid = seedScript();
        Long tid = seedTenant("preview-tenant");
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        req.setParams(Map.of("database", "cobp", "count", "30"));

        Map<String, Object> preview = executor.preview(req);

        assertEquals(sid, preview.get("scriptId"));
        assertEquals("Preview", preview.get("scriptDisplayName"));
        @SuppressWarnings("unchecked")
        List<String> cmd = (List<String>) preview.get("command");
        // 命令首项是配置的 shell（scriptbox.shell-executable），不要在测试里写死 "bash"：
        // 部署目标是 Linux 走 PATH 上的 bash，本地开发用 -Pgit-bash 指向 Git Bash。
        assertEquals(props.getShellExecutable(), cmd.get(0));
        assertTrue(cmd.contains("--database"));
        assertTrue(cmd.contains("cobp"));
        assertTrue(cmd.contains("--count"));
        assertTrue(cmd.contains("30"));
        assertNotNull(preview.get("tenantName"));
        assertNotNull(preview.get("principal"));
        assertEquals(Boolean.FALSE, preview.get("kinitWrapped"));
        // No execution_history row should have been created
        assertEquals(0, historyMapper.selectList(null).size());
    }

    @Test
    void previewMasksSensitiveGlobalVariables() throws Exception {
        String visibleKey = "DRY_VISIBLE_" + System.nanoTime();
        String secretKey = "DRY_SECRET_" + System.nanoTime();
        variableService.create(visibleKey, "visible", "", false, true);
        variableService.create(secretKey, "real-secret", "", true, true);

        Long sid = seedScript();
        Long tid = seedTenant("preview-mask");
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);

        Map<String, Object> preview = executor.preview(req);
        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) preview.get("globalVariables");
        assertEquals("visible", env.get(visibleKey));
        assertEquals("******", env.get(secretKey));
    }

    @Test
    void previewFailsForUnknownScript() {
        Long tid = seedTenant("preview-bad");
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(999999L);
        req.setTenantId(tid);
        Exception ex = assertThrows(IllegalArgumentException.class, () -> executor.preview(req));
        assertTrue(ex.getMessage().contains("script not found"));
    }
}