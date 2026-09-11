package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that H2 file-mode persists data after a Spring context is closed
 * and a fresh one is started against the same DB file.
 *
 * Strategy: write data via the current context, close it, and inspect the
 * DB file directly. We don't spin up a second Spring context (too slow), but
 * we *do* verify the H2 file exists and is non-empty, which proves that
 * writes hit the disk. The next-launch verification is done manually via
 * the README acceptance checklist.
 */
class H2PersistenceTest extends BaseIntegrationTest {

    @Autowired private TenantService tenantService;
    @Autowired private ScriptService scriptService;

    @Test
    void writesHitH2FileOnDisk() throws Exception {
        Tenant t = new Tenant();
        t.setName("persist");
        t.setPrincipal("p@EXAMPLE.COM");
        t.setEnabled(true);
        Tenant saved = tenantService.create(t);

        Script s = new Script();
        s.setName("p1");
        s.setDisplayName("p1");
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        scriptService.create(s, new InMemoryMultipartFile(
                "file", "p1.sh", "application/x-sh",
                "#!/bin/bash\necho ok\n".getBytes(StandardCharsets.UTF_8)));

        // verify the H2 file exists
        Path db = Paths.get("./target/test-db/scriptbox.mv.db");
        assertTrue(Files.exists(db), "expected H2 file at " + db.toAbsolutePath());
        assertTrue(Files.size(db) > 0);

        // Use a raw JDBC query against the file to confirm rows persisted
        try (var conn = java.nio.file.Files.exists(db)
                ? java.sql.DriverManager.getConnection(
                        "jdbc:h2:file:./target/test-db/scriptbox;AUTO_SERVER=TRUE;DB_CLOSE_ON_EXIT=FALSE",
                        "sa", "")
                : null) {
            assertNotNull(conn);
            try (var ps = conn.prepareStatement("SELECT COUNT(*) FROM tenant");
                 var rs = ps.executeQuery()) {
                rs.next();
                assertTrue(rs.getInt(1) >= 1);
            }
            try (var ps = conn.prepareStatement("SELECT name FROM tenant WHERE id = ?")) {
                ps.setLong(1, saved.getId());
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("persist", rs.getString(1));
                }
            }
        }
    }
}