package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptVersion;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.ScriptVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScriptVersionServiceTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private ScriptVersionService versionService;

    private Long seed(String body) throws IOException {
        Script s = new Script();
        s.setName("vs-" + System.nanoTime());
        s.setDisplayName("ver-test");
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", "v.sh", "application/x-sh", body.getBytes(StandardCharsets.UTF_8)));
        return saved.getId();
    }

    @Test
    void createSeedsV1() throws Exception {
        Long id = seed("#!/bin/bash\necho v1\n");
        List<ScriptVersion> vs = versionService.listByScript(id);
        assertEquals(1, vs.size());
        assertEquals(1, vs.get(0).getVersionNo());
        assertTrue(vs.get(0).getScriptContent().contains("v1"));
    }

    @Test
    void saveBodyAppendsNewVersion() throws Exception {
        Long id = seed("#!/bin/bash\necho v1\n");
        scriptService.saveScriptBody(id, "#!/bin/bash\necho v2\n");
        scriptService.saveScriptBody(id, "#!/bin/bash\necho v3\n");
        List<ScriptVersion> vs = versionService.listByScript(id);
        assertEquals(3, vs.size());
        // listByScript returns DESC
        assertEquals(3, vs.get(0).getVersionNo());
        assertEquals(2, vs.get(1).getVersionNo());
        assertEquals(1, vs.get(2).getVersionNo());
        // Body is now v3
        String currentBody = scriptService.readScriptBody(id);
        assertTrue(currentBody.contains("v3"));
    }

    @Test
    void rollbackCreatesNewVersionWithOldContent() throws Exception {
        Long id = seed("#!/bin/bash\necho v1\n");
        scriptService.saveScriptBody(id, "#!/bin/bash\necho v2\n");
        scriptService.saveScriptBody(id, "#!/bin/bash\necho v3\n");
        // Roll back to v1
        ScriptVersion created = versionService.rollback(id, 1);
        assertNotNull(created.getId());
        assertEquals(4, created.getVersionNo());
        assertTrue(created.getRemark().contains("rollback"));
        assertTrue(created.getScriptContent().contains("v1"));
        // History still has all 4 entries
        List<ScriptVersion> vs = versionService.listByScript(id);
        assertEquals(4, vs.size());
        // Current body == v1
        assertTrue(scriptService.readScriptBody(id).contains("v1"));
    }

    @Test
    void rollbackUnknownVersionFails() throws Exception {
        Long id = seed("#!/bin/bash\necho v1\n");
        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> versionService.rollback(id, 99));
        assertTrue(ex.getMessage().contains("version not found"));
    }
}