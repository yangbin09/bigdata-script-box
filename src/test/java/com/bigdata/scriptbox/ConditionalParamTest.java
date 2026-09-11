package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.model.VisibleWhen;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionalParamTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private ScriptExecutor executor;

    private Long seedScript(String name, List<ScriptParam> params) throws Exception {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(name);
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", name + ".sh", "application/x-sh",
                "#!/bin/bash\nfor arg in \"$@\"; do echo \"$arg\"; done\n".getBytes(StandardCharsets.UTF_8)));
        scriptService.replaceParams(saved.getId(), params);
        return saved.getId();
    }

    private Long seedTenant() {
        Tenant t = new Tenant();
        t.setName("cp-tenant-" + System.nanoTime());
        t.setPrincipal("cp@EXAMPLE.COM");
        t.setEnabled(true);
        return tenantService.create(t).getId();
    }

    private ScriptParam p(String name, String type, String defaultValue, String visibleWhenJson) {
        ScriptParam sp = new ScriptParam();
        sp.setName(name);
        sp.setType(type);
        sp.setDefaultValue(defaultValue);
        sp.setVisibleWhenJson(visibleWhenJson);
        sp.setRequired(false);
        sp.setSortOrder(0);
        return sp;
    }

    @Test
    void hiddenParamIsNotPassedToScript() throws Exception {
        // env=dev hides the prodDb param
        ScriptParam env = p("env", "select", "dev", null);
        env.setOptions("dev,prod");
        ScriptParam prodDb = p("prodDb", "text", "", "{\"param\":\"env\",\"operator\":\"equals\",\"value\":\"prod\"}");
        Long sid = seedScript("cond-hide", List.of(env, prodDb));
        Long tid = seedTenant();

        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        req.setParams(new LinkedHashMap<>(Map.of("env", "dev", "prodDb", "should-not-show")));

        ExecutionHistory h = executor.execute(req);
        assertTrue(h.getSuccess(), "exit=" + h.getExitCode());
        // prodDb should NOT appear in the parameters that the script saw.
        assertFalse(h.getParametersJson().contains("prodDb"),
                "hidden param prodDb leaked: " + h.getParametersJson());
        assertTrue(h.getParametersJson().contains("env"));
    }

    @Test
    void visibleParamIsPassedWhenRuleMatches() throws Exception {
        ScriptParam env = p("env", "select", "dev", null);
        env.setOptions("dev,prod");
        ScriptParam prodDb = p("prodDb", "text", "", "{\"param\":\"env\",\"operator\":\"equals\",\"value\":\"prod\"}");
        Long sid = seedScript("cond-show", List.of(env, prodDb));
        Long tid = seedTenant();

        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        req.setParams(new LinkedHashMap<>(Map.of("env", "prod", "prodDb", "warehouse_db")));

        ExecutionHistory h = executor.execute(req);
        assertTrue(h.getSuccess());
        assertTrue(h.getParametersJson().contains("prodDb"));
        assertTrue(h.getParametersJson().contains("warehouse_db"));
    }

    @Test
    void notEqualsInvertsVisibility() throws Exception {
        ScriptParam mode = p("mode", "select", "fast", null);
        mode.setOptions("fast,slow");
        ScriptParam slowArg = p("slowArg", "text", "", "{\"param\":\"mode\",\"operator\":\"notEquals\",\"value\":\"fast\"}");
        Long sid = seedScript("cond-not-equals", List.of(mode, slowArg));
        Long tid = seedTenant();

        // mode=fast ⇒ slowArg hidden
        ExecutionRequest req1 = new ExecutionRequest();
        req1.setScriptId(sid);
        req1.setTenantId(tid);
        req1.setParams(new LinkedHashMap<>(Map.of("mode", "fast", "slowArg", "leaked")));
        ExecutionHistory h1 = executor.execute(req1);
        assertTrue(h1.getSuccess());
        assertFalse(h1.getParametersJson().contains("slowArg"));

        // mode=slow ⇒ slowArg visible
        ExecutionRequest req2 = new ExecutionRequest();
        req2.setScriptId(sid);
        req2.setTenantId(tid);
        req2.setParams(new LinkedHashMap<>(Map.of("mode", "slow", "slowArg", "kept")));
        ExecutionHistory h2 = executor.execute(req2);
        assertTrue(h2.getSuccess());
        assertTrue(h2.getParametersJson().contains("slowArg"));
    }

    @Test
    void emptyVisibleWhenAlwaysShows() throws Exception {
        ScriptParam visible = p("visible", "text", "always", null);
        Long sid = seedScript("cond-default", List.of(visible));
        Long tid = seedTenant();

        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        req.setParams(new LinkedHashMap<>(Map.of("visible", "hello")));

        ExecutionHistory h = executor.execute(req);
        assertTrue(h.getSuccess());
        assertTrue(h.getParametersJson().contains("visible"));
        assertTrue(h.getParametersJson().contains("hello"));
    }

    @Test
    void referenceToUnknownParamRejectedAtSave() {
        ScriptParam env = p("env", "text", "x", null);
        ScriptParam bad = p("bad", "text", "", "{\"param\":\"doesNotExist\",\"operator\":\"equals\",\"value\":\"x\"}");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> scriptService.replaceParams(0L, List.of(env, bad)));
        assertTrue(ex.getMessage().toLowerCase().contains("unknown"),
                "should reject dangling reference: " + ex.getMessage());
    }

    @Test
    void malformedVisibleWhenJsonRejected() {
        ScriptParam env = p("env", "text", "x", null);
        ScriptParam bad = p("bad", "text", "", "{not-valid-json");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> scriptService.replaceParams(0L, List.of(env, bad)));
        assertTrue(ex.getMessage().toLowerCase().contains("visiblewhenjson")
                || ex.getMessage().toLowerCase().contains("invalid"),
                "should reject malformed JSON: " + ex.getMessage());
    }

    @Test
    void visibleWhenParseRoundTrip() {
        VisibleWhen rule = VisibleWhen.parse("{\"param\":\"env\",\"operator\":\"equals\",\"value\":\"prod\"}");
        assertNotNull(rule);
        assertEquals("env", rule.getParam());
        assertEquals("equals", rule.getOperator());
        assertEquals("prod", rule.getValue());
        assertTrue(rule.matches(Map.of("env", "prod")));
        assertFalse(rule.matches(Map.of("env", "dev")));

        // null/blank/malformed ⇒ null rule
        assertNull(VisibleWhen.parse(null));
        assertNull(VisibleWhen.parse(""));
        assertNull(VisibleWhen.parse("{not-json"));
    }

    @Test
    void visibleWhenIsVisibleHelperForNullRule() {
        // Static helper must handle null rule by returning true (always visible).
        assertTrue(VisibleWhen.isVisible(null, Map.of()));
    }
}
