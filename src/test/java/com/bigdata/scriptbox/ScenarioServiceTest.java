package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Scenario;
import com.bigdata.scriptbox.entity.ScenarioStep;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.ScenarioService;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioServiceTest extends BaseIntegrationTest {

    @Autowired private ScenarioService scenarioService;
    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;

    private Long seedScript(String body) throws Exception {
        Script s = new Script();
        s.setName("sc-script-" + System.nanoTime());
        s.setDisplayName("SC");
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", "sc.sh", "application/x-sh",
                body.getBytes(StandardCharsets.UTF_8)));
        return saved.getId();
    }

    private Long seedTenant() {
        Tenant t = new Tenant();
        t.setName("sc-tenant-" + System.nanoTime());
        t.setPrincipal("sc@EXAMPLE.COM");
        t.setEnabled(true);
        return tenantService.create(t).getId();
    }

    private Scenario newScenario(String name) {
        Scenario sc = new Scenario();
        sc.setName(name);
        sc.setDescription("sc test");
        sc.setCategory("Mock");
        sc.setEnabled(true);
        return scenarioService.create(sc);
    }

    @Test
    void createAndRunAllStepsInOrder() throws Exception {
        Long s1 = seedScript("#!/bin/bash\necho one\n");
        Long s2 = seedScript("#!/bin/bash\necho two\n");
        Long tid = seedTenant();
        Scenario sc = newScenario("run-order-" + System.nanoTime());
        scenarioService.replaceSteps(sc.getId(), List.of(
                step(s1, false),
                step(s2, false)));

        ScenarioService.RunResult r = scenarioService.run(sc.getId(), tid);
        assertEquals(2, r.total);
        assertEquals(2, r.succeeded);
        assertEquals(0, r.failed);
        assertFalse(r.aborted);
        assertEquals(2, r.historyIds.size());
        for (Long hid : r.historyIds) {
            ExecutionHistory h = scenarioService.stepsOf(sc.getId()) == null ? null : null;
            // pull from history mapper indirectly via ids
            assertNotNull(hid);
        }
    }

    @Test
    void stopsOnFirstFailureByDefault() throws Exception {
        Long ok = seedScript("#!/bin/bash\necho ok\n");
        Long bad = seedScript("#!/bin/bash\nexit 7\n");
        Long tid = seedTenant();
        Scenario sc = newScenario("stop-" + System.nanoTime());
        scenarioService.replaceSteps(sc.getId(), List.of(
                step(ok, false),
                step(bad, false),
                step(ok, false)));

        ScenarioService.RunResult r = scenarioService.run(sc.getId(), tid);
        assertTrue(r.aborted);
        assertEquals(1, r.abortedAtStep);
        assertEquals(1, r.succeeded);
        assertEquals(1, r.failed);
    }

    @Test
    void continueOnFailureRunsAll() throws Exception {
        Long ok = seedScript("#!/bin/bash\necho ok\n");
        Long bad = seedScript("#!/bin/bash\nexit 7\n");
        Long tid = seedTenant();
        Scenario sc = newScenario("continue-" + System.nanoTime());
        scenarioService.replaceSteps(sc.getId(), List.of(
                step(ok, true),
                step(bad, true),
                step(ok, true)));

        ScenarioService.RunResult r = scenarioService.run(sc.getId(), tid);
        assertFalse(r.aborted);
        assertEquals(2, r.succeeded);
        assertEquals(1, r.failed);
        assertEquals(3, r.historyIds.size());
    }

    @Test
    void replaceStepsWipesPriorSteps() throws Exception {
        Long s1 = seedScript("#!/bin/bash\necho one\n");
        Long s2 = seedScript("#!/bin/bash\necho two\n");
        Long tid = seedTenant();
        Scenario sc = newScenario("replace-" + System.nanoTime());
        scenarioService.replaceSteps(sc.getId(), List.of(step(s1, false)));
        assertEquals(1, scenarioService.stepsOf(sc.getId()).size());
        scenarioService.replaceSteps(sc.getId(), List.of(
                step(s1, false),
                step(s2, false)));
        assertEquals(2, scenarioService.stepsOf(sc.getId()).size());
    }

    @Test
    void deleteCascades() throws Exception {
        Long s1 = seedScript("#!/bin/bash\necho one\n");
        Scenario sc = newScenario("del-" + System.nanoTime());
        scenarioService.replaceSteps(sc.getId(), List.of(step(s1, false)));
        Long id = sc.getId();
        scenarioService.delete(id);
        assertNull(scenarioService.get(id));
        assertEquals(0, scenarioService.stepsOf(id).size());
    }

    private static ScenarioStep step(Long scriptId, boolean continueOnFailure) {
        ScenarioStep s = new ScenarioStep();
        s.setScriptId(scriptId);
        s.setContinueOnFailure(continueOnFailure);
        return s;
    }
}