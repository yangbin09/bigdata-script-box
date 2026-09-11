package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.service.ScriptPackageService;
import com.bigdata.scriptbox.service.ScriptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ScriptPackageServiceTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private ScriptPackageService packageService;

    private Script seed(String name, String body, List<ScriptParam> params) throws IOException {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName("DP_" + name);
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", name + ".sh", "application/x-sh", bytes));
        if (!params.isEmpty()) scriptService.replaceParams(saved.getId(), params);
        return saved;
    }

    @Test
    void exportAndImportRoundTrip() throws Exception {
        var p = new ScriptParam();
        p.setName("database"); p.setType("text"); p.setDefaultValue("def");
        Script orig = seed("orig-" + System.nanoTime(),
                "#!/bin/bash\necho roundtrip\n", List.of(p));

        byte[] zip = packageService.export(orig.getId());
        assertNotNull(zip);
        assertTrue(zip.length > 0);

        // Sanity: zip has 3 entries
        int entries = 0;
        try (var zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((zin.getNextEntry()) != null) entries++;
        }
        assertEquals(3, entries);

        // Now import into a fresh slot; because the original name is unique
        // and not present in target (we use a fresh test DB), the import
        // creates a new row.
        ScriptPackageService.ImportResult r = packageService.doImport(
                new InMemoryMultipartFile("file", "pkg.zip", "application/zip", zip),
                false);
        assertNotNull(r.script);
        assertTrue(r.created);
        // Self-import: source name already exists in the DB, so it becomes
        // a copy. That's the safe default behaviour.
        assertTrue(r.copied);
        // Body roundtrip
        String body = scriptService.readScriptBody(r.script.getId());
        assertTrue(body.contains("roundtrip"));
        // Params roundtrip
        List<ScriptParam> pp = scriptService.paramsOf(r.script.getId());
        assertEquals(1, pp.size());
        assertEquals("database", pp.get(0).getName());
    }

    @Test
    void nameCollisionCreatesCopy() throws Exception {
        String collisionName = "collision-" + System.nanoTime();
        seed(collisionName, "#!/bin/bash\necho first\n", List.of());
        Script orig = seed(collisionName, "#!/bin/bash\necho second\n", List.of());
        // Now export second one and try to import — the name already exists.
        byte[] zip = packageService.export(orig.getId());
        ScriptPackageService.ImportResult r = packageService.doImport(
                new InMemoryMultipartFile("file", "pkg.zip", "application/zip", zip),
                false);
        assertTrue(r.copied, "should have been renamed because of collision");
        assertTrue(r.script.getName().startsWith(collisionName));
        assertTrue(r.script.getName().contains("copy"));
    }

    @Test
    void rejectsZipSlip() throws Exception {
        // Build a malicious zip with ../escape entry
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry e = new ZipEntry("../escape.sh");
            zos.putNextEntry(e);
            zos.write("malicious".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            ZipEntry m = new ZipEntry("manifest.json");
            zos.putNextEntry(m);
            zos.write("{\"name\":\"evil\"}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            ZipEntry s = new ZipEntry("script.sh");
            zos.putNextEntry(s);
            zos.write("#!/bin/bash\necho bad\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> packageService.doImport(new InMemoryMultipartFile(
                        "file", "evil.zip", "application/zip", baos.toByteArray()), false));
        assertTrue(ex.getMessage().contains("invalid")
                || ex.getMessage().contains("slip"));
    }

    @Test
    void rejectsMissingManifest() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry s = new ZipEntry("script.sh");
            zos.putNextEntry(s);
            zos.write("#!/bin/bash\necho bad\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> packageService.doImport(new InMemoryMultipartFile(
                        "file", "no-manifest.zip", "application/zip", baos.toByteArray()), false));
        assertTrue(ex.getMessage().contains("manifest"));
    }

    @Test
    void rejectsMissingScript() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry m = new ZipEntry("manifest.json");
            zos.putNextEntry(m);
            zos.write("{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> packageService.doImport(new InMemoryMultipartFile(
                        "file", "no-script.zip", "application/zip", baos.toByteArray()), false));
        assertTrue(ex.getMessage().contains("script.sh"));
    }
}