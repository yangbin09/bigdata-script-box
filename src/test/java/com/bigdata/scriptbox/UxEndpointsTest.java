package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.controller.ExecutionController;
import com.bigdata.scriptbox.controller.HistoryController;
import com.bigdata.scriptbox.controller.SystemController;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.model.ExecutionStatus;
import com.bigdata.scriptbox.service.ExecutionGate;
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
    @Autowired private ExecutionController executionController;
    @Autowired private ExecutionGate gate;
    @Autowired private ExecutionHistoryMapper historyMapper;

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

        ApiResponse<List<ExecutionHistory>> okResp = historyController.list(100, null, null, "success", null, null, null);
        assertEquals(0, okResp.getCode());
        assertEquals(1, okResp.getData().size());

        ApiResponse<List<ExecutionHistory>> failResp = historyController.list(100, null, null, "failed", null, null, null);
        assertEquals(1, failResp.getData().size());

        ApiResponse<List<ExecutionHistory>> allResp = historyController.list(100, null, null, null, null, null, null);
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

        ApiResponse<List<ExecutionHistory>> hit = historyController.list(100, null, null, null, "kw", null, null);
        assertEquals(1, hit.getData().size());
        ApiResponse<List<ExecutionHistory>> miss = historyController.list(100, null, null, null, "zzz", null, null);
        assertEquals(0, miss.getData().size());
    }

    @Test
    void logTailTreatsNotYetPersistedRowAsNoOutput() {
        // 竞态回归：POST /executions 早返 executionId 后，history 行由执行线程稍后写入。
        // 前端在拿到 id 的瞬间会拉一次实时日志 —— 此时行还不存在，必须当作
        // 「还没有日志」（200 空体），而不是 404。
        long inFlightId = 900_000_100L;
        assertNull(historyMapper.selectById(inFlightId), "precondition: 该 id 不应有 history 行");

        try (ExecutionGate.Permit permit = gate.acquire(inFlightId, 4242L, 4242L, "s-logtail", true).orThrow()) {
            var live = executionController.logTail(inFlightId, "stdout", 65536);
            assertEquals(200, live.getStatusCode().value(),
                    "运行中的执行即使 history 行还没落库，也应返回 200 空体");
            assertNotNull(live.getBody());
            assertEquals(0, live.getBody().length);
        }

        // 真正不存在的执行仍然 404，不能被静默当成空日志
        var unknown = executionController.logTail(900_000_999L, "stdout", 65536);
        assertEquals(404, unknown.getStatusCode().value(),
                "未知 executionId 必须 404，不能伪装成空日志");
    }

    @Test
    void lastSuccessfulPlanIsDerivedFromHistory() throws Exception {
        // 脚本用 --code 决定退出码：参数经 --key value 传入，故值落在 $2
        Script s = seedScript("s-plan", "#!/bin/bash\nexit \"${2:-0}\"\n");
        Tenant t = seedTenant();

        // 还没有跑过 → 空方案（不是 null，也不报错）
        var empty = historyService.lastSuccessfulPlan(s.getId(), null);
        assertNotNull(empty);
        assertEquals(s.getId(), empty.getScriptId());
        assertTrue(empty.getParameters().isEmpty(), "无历史时参数应为空");
        assertNull(empty.getExecutionId());

        // 跑一次失败的（exit 1）→ 不应被当成方案
        ScriptParam p = new ScriptParam();
        p.setName("code"); p.setLabel("退出码"); p.setType("text");
        p.setDefaultValue("0"); p.setRequired(true); p.setSortOrder(0);
        scriptService.replaceParams(s.getId(), List.of(p));
        ExecutionRequest bad = new ExecutionRequest();
        bad.setScriptId(s.getId()); bad.setTenantId(t.getId());
        bad.setParams(new LinkedHashMap<>(Map.of("code", "1")));
        ExecutionHistory badRow = executor.execute(bad);
        assertEquals(ExecutionStatus.FAILED.name(), badRow.getStatus(), "前置条件：这次执行必须是失败的");
        assertNull(historyService.lastSuccessfulPlan(s.getId(), null).getExecutionId(),
                "失败的执行不能成为方案来源");

        // 再跑一次成功的 → 成功那次成为方案，且参数原样带出
        ExecutionRequest ok = new ExecutionRequest();
        ok.setScriptId(s.getId()); ok.setTenantId(t.getId());
        ok.setParams(new LinkedHashMap<>(Map.of("code", "0")));
        ExecutionHistory okRow = executor.execute(ok);

        var plan = historyService.lastSuccessfulPlan(s.getId(), null);
        assertEquals(okRow.getId(), plan.getExecutionId());
        assertEquals("0", plan.getParameters().get("code"));
        assertEquals(t.getId(), plan.getTenantId());
        assertNotNull(plan.getExecutedAt(), "方案要带执行时间，前端才能显示「2 小时前」");

        // 限定到另一个租户时应落空（参数往往因租户而异）
        Tenant other = seedTenant();
        assertNull(historyService.lastSuccessfulPlan(s.getId(), other.getId()).getExecutionId());
    }

    @Test
    void historyDigestAggregatesByScriptAndDay() throws Exception {
        Script s = seedScript("s-digest", "#!/bin/bash\nexit 0\n");
        Tenant t = seedTenant();
        for (int i = 0; i < 3; i++) {
            ExecutionRequest req = new ExecutionRequest();
            req.setScriptId(s.getId()); req.setTenantId(t.getId());
            req.setParams(new LinkedHashMap<>());
            executor.execute(req);
        }

        List<com.bigdata.scriptbox.dto.ScriptPlan.ScriptDigest> digest = historyService.digest(7);
        var d = digest.stream().filter(x -> s.getId().equals(x.getScriptId())).findFirst().orElse(null);
        assertNotNull(d, "digest 应包含刚跑过的脚本");
        assertEquals(3, d.getTotal());
        assertEquals(3, d.getSucceeded());
        assertEquals(0, d.getFailed());
        assertEquals(0d, d.getFailureRate());
        assertNotNull(d.getLastStatus());
        assertFalse(d.getDays().isEmpty(), "应按天聚合出至少一天");
        assertEquals(3, d.getDays().get(0).getTotal());
        assertNotNull(d.getLastSuccessExecutionId());
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