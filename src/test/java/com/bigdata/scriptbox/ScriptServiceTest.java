package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.service.ScriptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScriptServiceTest extends BaseIntegrationTest {

    @Autowired
    private ScriptService scriptService;

    @Test
    void createWithParamsAndUpdate() throws IOException {
        Script s = new Script();
        s.setName("test1");
        s.setDisplayName("测试1");
        s.setCategory("Mock");
        s.setDescription("d");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        byte[] body = "#!/usr/bin/env bash\necho hi\n".getBytes(StandardCharsets.UTF_8);
        InMemoryMultipartFile mf = new InMemoryMultipartFile("file", "test1.sh", "application/x-sh", body);
        Script saved = scriptService.create(s, mf);
        assertNotNull(saved.getId());
        assertNotNull(saved.getScriptPath());
        assertTrue(saved.getScriptPath().endsWith("script.sh"));

        ScriptParam p1 = new ScriptParam();
        p1.setName("database");
        p1.setLabel("数据库");
        p1.setType("text");
        p1.setDefaultValue("default");
        p1.setRequired(true);
        p1.setSortOrder(0);

        ScriptParam p2 = new ScriptParam();
        p2.setName("tableType");
        p2.setLabel("表类型");
        p2.setType("select");
        p2.setDefaultValue("MOR");
        p2.setOptions("MOR,COW");
        p2.setSortOrder(1);

        List<ScriptParam> list = new ArrayList<>();
        list.add(p1); list.add(p2);
        List<ScriptParam> after = scriptService.replaceParams(saved.getId(), list);
        assertEquals(2, after.size());

        // Save new body via inline editor
        Script bodyUpdated = scriptService.saveScriptBody(saved.getId(), "#!/bin/bash\necho updated\n");
        assertEquals(bodyUpdated.getId(), saved.getId());

        // Delete cleans script + params
        scriptService.delete(saved.getId());
        assertEquals(0, scriptService.paramsOf(saved.getId()).size());
        assertNull(scriptService.getById(saved.getId()));
    }

    @Test
    void paramValidationRejectsBadType() throws IOException {
        Script s = scriptService.create(buildScript("s1"), new InMemoryMultipartFile(
                "file", "s1.sh", "application/x-sh",
                "#!/bin/bash\necho ok\n".getBytes(StandardCharsets.UTF_8)));
        ScriptParam p = new ScriptParam();
        p.setName("bad");
        p.setType("nope");
        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> scriptService.replaceParams(s.getId(), List.of(p)));
        assertTrue(ex.getMessage().contains("invalid param type"));
    }

    private Script buildScript(String name) {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(name);
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        return s;
    }
}