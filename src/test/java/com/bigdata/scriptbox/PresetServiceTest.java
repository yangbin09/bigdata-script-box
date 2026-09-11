package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.ScriptPreset;
import com.bigdata.scriptbox.service.PresetService;
import com.bigdata.scriptbox.service.ScriptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PresetServiceTest extends BaseIntegrationTest {

    @Autowired private PresetService presetService;
    @Autowired private ScriptService scriptService;

    private Long seedScript() throws IOException {
        Script s = new Script();
        s.setName("preset-script");
        s.setDisplayName("PresetScript");
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", "preset.sh", "application/x-sh",
                "#!/bin/bash\necho ok\n".getBytes(StandardCharsets.UTF_8)));
        // 3 declared params: database, count, mode
        ScriptParam p1 = new ScriptParam(); p1.setName("database"); p1.setType("text"); p1.setDefaultValue("def");
        ScriptParam p2 = new ScriptParam(); p2.setName("count");    p2.setType("number"); p2.setDefaultValue("10");
        ScriptParam p3 = new ScriptParam(); p3.setName("mode");     p3.setType("select"); p3.setOptions("a,b,c"); p3.setDefaultValue("a");
        scriptService.replaceParams(saved.getId(), List.of(p1, p2, p3));
        return saved.getId();
    }

    @Test
    void createAndListPresets() throws Exception {
        Long sid = seedScript();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("database", "cobp");
        params.put("count", "30");
        ScriptPreset created = presetService.create(sid, "30天TTL", "30天测试", params);
        assertNotNull(created.getId());
        List<ScriptPreset> list = presetService.listByScript(sid);
        assertEquals(1, list.size());
        assertEquals("30天TTL", list.get(0).getName());
    }

    @Test
    void applyPreservesValues() throws Exception {
        Long sid = seedScript();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("database", "cobp");
        params.put("count", 30);
        params.put("mode", "b");
        ScriptPreset p = presetService.create(sid, "default", "", params);
        Map<String, String> applied = presetService.applyParams(p);
        assertEquals("cobp", applied.get("database"));
        assertEquals("30", applied.get("count"));
        assertEquals("b", applied.get("mode"));
    }

    @Test
    void applyParamsRoundTripsRawJson() throws Exception {
        // applyParams returns the raw saved snapshot; the executor (validateAndCoerce)
        // and the frontend form both iterate over the CURRENT ScriptParam declaration
        // so unknown keys are dropped at use time, not at apply time.
        Long sid = seedScript();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("database", "cobp");
        params.put("removed_key", "X"); // no longer declared
        params.put("count", "5");
        ScriptPreset p = presetService.create(sid, "mix", "", params);
        Map<String, String> applied = presetService.applyParams(p);
        assertEquals("cobp", applied.get("database"));
        assertEquals("5", applied.get("count"));
        assertEquals("X", applied.get("removed_key"),
                "applyParams is a JSON round-trip; unknown keys remain visible here " +
                "and are filtered at execute time against declared params");
    }

    @Test
    void updateOverwritesParamsAndName() throws Exception {
        Long sid = seedScript();
        ScriptPreset p = presetService.create(sid, "old", "d",
                Map.of("database", "a", "count", "1", "mode", "a"));
        Map<String, Object> np = new LinkedHashMap<>();
        np.put("database", "b");
        np.put("count", "9");
        np.put("mode", "c");
        ScriptPreset updated = presetService.update(sid, p.getId(), "new", "d2", np);
        assertEquals("new", updated.getName());
        assertEquals("b", presetService.applyParams(updated).get("database"));
        assertEquals("9", presetService.applyParams(updated).get("count"));
    }

    @Test
    void deleteRemoves() throws Exception {
        Long sid = seedScript();
        ScriptPreset p = presetService.create(sid, "tmp", "", Map.of("database", "x"));
        presetService.delete(sid, p.getId());
        assertTrue(presetService.listByScript(sid).isEmpty());
        assertNull(presetService.get(sid, p.getId()));
    }

    @Test
    void summaryReturnsIdAndNameOnly() throws Exception {
        Long sid = seedScript();
        presetService.create(sid, "p1", "", Map.of("database", "x"));
        presetService.create(sid, "p2", "", Map.of("database", "y"));
        var s = presetService.listSummary(sid);
        assertEquals(2, s.size());
        assertTrue(s.get(0).containsKey("id"));
        assertTrue(s.get(0).containsKey("name"));
        // raw params_json should NOT leak in summary
        assertFalse(s.get(0).containsKey("paramsJson"));
    }
}