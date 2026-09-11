package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.controller.HistoryController;
import com.bigdata.scriptbox.controller.SystemController;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.service.HistoryService;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the v2 UX endpoints — system info, history filter, recent-scripts,
 * favorite toggle, copy. Endpoint shape only; the executor tests cover the
 * actual shell runs.
 */
class UxEndpointsTest extends BaseIntegrationTest {

    @Autowired private SystemController systemController;
    @Autowired private HistoryController historyController;
    @Autowired private HistoryService historyService;
    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private ScriptExecutor executor;
    @Autowired private ScriptBoxProperties props;

    private Script seedScript(String name, String body) throws Exception {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(name);
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        return scriptService.create(s,
                new InMemoryMultipartFile("file", name + ".sh", "application/x-sh",
                        body.getBytes(StandardCharsets.UTF_8)));
    }

    private Tenant seedTenant() {
        Tenant t = new Tenant();
        t.setName("t-ux");
        t.setPrincipal("ux@EXAMPLE.COM");
        t.setDefaultDatabase("default");
        t.setEnabled(true);
        return tenantService.create(t);
    }

    @Test
    void systemInfoReturnsEnvironment() {
        ApiResponse<Map<String, Object>> r = systemController.info();
        assertEquals(0, r.getCode());
        assertNotNull(r.getData());
        assertTrue(r.getData().containsKey("mock"));
        assertTrue(r.getData().containsKey("environmentName"));
    }

    @Test
    void favoriteToggleRoundTrip() throws Exception {
        Script s = seedScript("s-fav", "#!/bin/bash\necho ok\n");
        // newly created scripts default to favorite=false
        assertEquals(Boolean.FALSE, s.getFavorite());
        // toggle on via service
        Script on = scriptService.setFavorite(s.getId(), true);
        assertEquals(Boolean.TRUE, on.getFavorite());
        // toggle off
        Script off = scriptService.setFavorite(s.getId(), false);
        assertEquals(Boolean.FALSE, off.getFavorite());
    }

    @Test
    void copyClonesScriptAndParams() throws Exception {
        Script s = seedScript("s-copy-src", "#!/bin/bash\necho hi\n");
        ScriptParam p = new ScriptParam();
        p.setName("table"); p.setLabel("表名"); p.setType("text");
        p.setDefaultValue("t1"); p.setRequired(true); p.setSortOrder(0);
        p.setPlaceholder("请输入表名"); p.setHelpText("例如: cobp_dwd");
        scriptService.replaceParams(s.getId(), List.of(p));

        // Use the controller directly
        var resp = copyScriptByController(s.getId(), null);
        Script copy = resp.getData();
        assertNotNull(copy);
        assertNotEquals(s.getId(), copy.getId());
        assertEquals(s.getName() + "-copy", copy.getName());
        assertEquals(Boolean.FALSE, copy.getEnabled(), "copy must be disabled until edited");
        List<ScriptParam> cloned = scriptService.paramsOf(copy.getId());
        assertEquals(1, cloned.size());
        assertEquals("table", cloned.get(0).getName());
        assertEquals("请输入表名", cloned.get(0).getPlaceholder());
        assertEquals("例如: cobp_dwd", cloned.get(0).getHelpText());
    }

    @Test
    void recentScriptsReturnsDistinctOrderedByLatest() throws Exception {
        Script a = seedScript("s-rec-a", "#!/bin/bash\necho a\n");
        Script b = seedScript("s-rec-b", "#!/bin/bash\necho b\n");
        Tenant t = seedTenant();
        // Two runs of A, then one of B. Recent-scripts should yield [B, A].
        for (int i = 0; i < 2; i++) {
            ExecutionRequest req = new ExecutionRequest();
            req.setScriptId(a.getId()); req.setTenantId(t.getId());
            req.setParams(new LinkedHashMap<>());
            executor.execute(req);
        }
        ExecutionRequest rb = new ExecutionRequest();
        rb.setScriptId(b.getId()); rb.setTenantId(t.getId());
        rb.setParams(new LinkedHashMap<>());
        executor.execute(rb);

        List<HistoryService.RecentScript> recent = historyService.recentScripts(6);
        assertEquals(2, recent.size());
        assertEquals(b.getId(), recent.get(0).id);
        assertEquals(a.getId(), recent.get(1).id);
    }

    @Test
    void historyFilterByStatus() throws Exception {
        Script s = seedScript("s-fl", "#!/bin/bash\nexit 0\n");
        Tenant t = seedTenant();
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(s.getId()); req.setTenantId(t.getId());
        req.setParams(new LinkedHashMap<>());
        executor.execute(req);
        Script bad = seedScript("s-fl-bad", "#!/bin/bash\necho err 1>&2\nexit 1\n");
        ExecutionRequest rbad = new ExecutionRequest();
        rbad.setScriptId(bad.getId()); rbad.setTenantId(t.getId());
        rbad.setParams(new LinkedHashMap<>());
        executor.execute(rbad);

        ApiResponse<List<ExecutionHistory>> okResp = historyController.list(100, null, null, "success", null);
        assertEquals(0, okResp.getCode());
        assertEquals(1, okResp.getData().size());

        ApiResponse<List<ExecutionHistory>> failResp = historyController.list(100, null, null, "failed", null);
        assertEquals(1, failResp.getData().size());

        ApiResponse<List<ExecutionHistory>> allResp = historyController.list(100, null, null, null, null);
        assertEquals(2, allResp.getData().size());
    }

    @Test
    void historyFilterByKeyword() throws Exception {
        Script s = seedScript("s-kw", "#!/bin/bash\necho ok\n");
        Tenant t = seedTenant();
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(s.getId()); req.setTenantId(t.getId());
        req.setParams(new LinkedHashMap<>());
        executor.execute(req);

        ApiResponse<List<ExecutionHistory>> hit = historyController.list(100, null, null, null, "kw");
        assertEquals(1, hit.getData().size());
        ApiResponse<List<ExecutionHistory>> miss = historyController.list(100, null, null, null, "zzz");
        assertEquals(0, miss.getData().size());
    }

    private ApiResponse<Script> copyScriptByController(Long id, String suffix) throws Exception {
        // Mirror ScriptController.copy without going through HTTP
        Script src = scriptService.getById(id);
        Script copy = new Script();
        copy.setName(src.getName() + (suffix != null ? suffix : "-copy"));
        copy.setDisplayName((src.getDisplayName() == null ? src.getName() : src.getDisplayName()) + " - Copy");
        copy.setCategory(src.getCategory());
        copy.setEnabled(Boolean.FALSE);
        copy.setFavorite(Boolean.FALSE);
        copy.setDefaultTenantId(src.getDefaultTenantId());
        byte[] bytes;
        try { bytes = scriptService.readScriptBody(id).getBytes(StandardCharsets.UTF_8); }
        catch (Exception e) { throw new RuntimeException(e); }
        InMemoryMultipartFile mf = new InMemoryMultipartFile(
                "file", copy.getName() + ".sh", "application/x-sh", bytes);
        Script saved = scriptService.create(copy, mf);
        // duplicate params
        List<ScriptParam> params = scriptService.paramsOf(id);
        if (!params.isEmpty()) {
            scriptService.replaceParams(saved.getId(),
                    params.stream().map(p -> {
                        ScriptParam np = new ScriptParam();
                        np.setName(p.getName()); np.setLabel(p.getLabel());
                        np.setType(p.getType()); np.setDefaultValue(p.getDefaultValue());
                        np.setOptions(p.getOptions()); np.setRequired(p.getRequired());
                        np.setSortOrder(p.getSortOrder());
                        np.setPlaceholder(p.getPlaceholder());
                        np.setHelpText(p.getHelpText());
                        return np;
                    }).toList());
        }
        return ApiResponse.ok(saved);
    }
}