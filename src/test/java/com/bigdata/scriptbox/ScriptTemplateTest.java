package com.bigdata.scriptbox;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptTemplate;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.ScriptTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScriptTemplateTest extends BaseIntegrationTest {

    @Autowired private ScriptTemplateService templateService;
    @Autowired private ScriptService scriptService;

    @Test
    void fiveBuiltInTemplatesSeeded() {
        List<ScriptTemplate> all = templateService.listAll();
        // DataInitializer seeds 5 (plain-shell, kerberos, spark-sql, hdfs, yarn).
        assertEquals(5, all.size(), "expected 5 built-in templates, got " + all.size());
        // Each has a unique code, non-empty content, and enabled=true.
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (ScriptTemplate t : all) {
            assertNotNull(t.getCode(), "code is required");
            assertTrue(codes.add(t.getCode()), "duplicate code: " + t.getCode());
            assertNotNull(t.getContent());
            assertTrue(t.getContent().length() > 0, "empty content: " + t.getCode());
            assertTrue(t.getContent().startsWith("#!/bin/bash"),
                    "template should be bash, got: " + t.getContent().substring(0, Math.min(20, t.getContent().length())));
            assertTrue(Boolean.TRUE.equals(t.getEnabled()),
                    "seeded template must be enabled: " + t.getCode());
        }
    }

    @Test
    void listEnabledReturnsSameAsListAllAfterSeed() {
        assertEquals(templateService.listAll().size(), templateService.listEnabled().size());
    }

    @Test
    void findByCodeRoundTrip() {
        ScriptTemplate t = templateService.findByCode("kerberos");
        assertNotNull(t);
        assertEquals("kerberos", t.getCode());
        assertTrue(t.getContent().contains("kinit"),
                "kerberos template should mention kinit");
        assertNotNull(t.getParamsJson(), "kerberos template should declare params");
    }

    @Test
    void findByCodeUnknownReturnsNull() {
        assertNull(templateService.findByCode("not-a-real-template"));
        assertNull(templateService.findByCode(null));
    }

    @Test
    void createFromTemplateProducesRealScript() {
        ScriptTemplate tpl = templateService.findByCode("plain-shell");
        assertNotNull(tpl);

        // Mimic what the controller does: create a Script using an
        // InMemoryMultipartFile carrying the template's content.
        Script s = new Script();
        s.setName("tpl-test-" + System.nanoTime());
        s.setDisplayName("Template Test Script");
        s.setCategory(tpl.getCategory());
        s.setDescription(tpl.getDescription());
        s.setTimeoutSeconds(600);
        s.setEnabled(true);
        Script saved;
        try {
            saved = scriptService.create(s, new com.bigdata.scriptbox.config.InMemoryMultipartFile(
                    "file", s.getName() + ".sh", "application/x-sh",
                    tpl.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertNotNull(saved.getId());
        // The plain-shell template declares a single 'name' param. Apply it
        // here directly (the controller does this via scriptService.replaceParams)
        // so we exercise the param-application code path even from the test.
        if (tpl.getParamsJson() != null && !tpl.getParamsJson().isBlank()) {
            try {
                List<com.bigdata.scriptbox.entity.ScriptParam> params =
                        new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                                tpl.getParamsJson(),
                                new com.fasterxml.jackson.core.type.TypeReference<List<com.bigdata.scriptbox.entity.ScriptParam>>() {});
                scriptService.replaceParams(saved.getId(), params);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        assertEquals(1, scriptService.paramsOf(saved.getId()).size(),
                "template's params should be applied");
    }

    @Test
    void templateIdempotentUpsert() {
        ScriptTemplate existing = templateService.findByCode("hdfs");
        assertNotNull(existing);
        long countBefore = templateService.listAll().size();

        // Upserting with the same code shouldn't create a duplicate.
        ScriptTemplate t = new ScriptTemplate();
        t.setCode("hdfs");
        t.setName("HDFS Operations (updated)");
        t.setCategory("Storage");
        t.setDescription("updated");
        t.setContent(existing.getContent());
        t.setSortOrder(99);
        t.setEnabled(true);
        templateService.createOrUpdate(t);

        long countAfter = templateService.listAll().size();
        assertEquals(countBefore, countAfter,
                "createOrUpdate should upsert, not duplicate");
        ScriptTemplate after = templateService.findByCode("hdfs");
        assertEquals("HDFS Operations (updated)", after.getName(),
                "name should be updated");
    }

    @Test
    void apiResponseShapeMatchesEntity() {
        // Sanity check that controller serialization doesn't trip on missing
        // fields. We can't easily invoke the controller without a web env,
        // but we can at least confirm the entity is well-formed for every
        // template.
        for (ScriptTemplate t : templateService.listAll()) {
            assertNotNull(t.getCode());
            assertNotNull(t.getName());
            // enabled must default-construct to a sensible value (true)
            assertTrue(Boolean.TRUE.equals(t.getEnabled()));
        }
    }
}