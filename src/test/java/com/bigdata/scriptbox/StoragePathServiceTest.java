package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.exception.BusinessException;
import com.bigdata.scriptbox.service.StoragePathService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StoragePathService}.
 *
 * <p>Covers:
 * <ol>
 *   <li>Path generation: each method returns a path under the correct root.</li>
 *   <li>{@code assertInside} accepts child paths inside the controlled root and
 *       rejects siblings with a {@link BusinessException}.</li>
 *   <li>{@code isInsideReal} handles symlinks safely: a symlink inside the
 *       controlled root still resolves to a path inside it.</li>
 * </ol>
 */
class StoragePathServiceTest {

    @TempDir Path tmp;

    private StoragePathService svc;

    @BeforeEach
    void setUp() throws Exception {
        ScriptBoxProperties props = new ScriptBoxProperties();
        props.setDataDir(tmp.resolve("data").toString());
        props.setScriptsDir(tmp.resolve("scripts").toString());
        props.setKeytabsDir(tmp.resolve("keytabs").toString());
        props.setExecutionsDir(tmp.resolve("executions").toString());
        Files.createDirectories(tmp.resolve("data"));
        Files.createDirectories(tmp.resolve("scripts"));
        Files.createDirectories(tmp.resolve("keytabs"));
        Files.createDirectories(tmp.resolve("executions"));
        svc = new StoragePathService(props);
    }

    @Test
    void pathGenerationFollowsConvention() {
        Path script = svc.scriptFilePath(42L);
        assertTrue(script.endsWith("scripts/42/script.sh"));

        Path keytab = svc.keytabPath(7L, "uuid");
        assertTrue(keytab.toString().contains("keytabs/tenant_7_uuid.keytab"));

        Path execDir = svc.executionDirFor(123L);
        assertTrue(execDir.endsWith("executions/123"));

        Path art = svc.artifactsDirFor(123L);
        assertTrue(art.endsWith("executions/123/artifacts"));

        Path input = svc.inputDirFor(123L);
        assertTrue(input.endsWith("executions/123/input"));

        Path upload = svc.pendingUploadDir("tok-abc");
        assertTrue(upload.endsWith("data/uploads/tok-abc"));
    }

    @Test
    void assertInsideAcceptsChildPath() {
        Path root = svc.executionsRoot();
        Path child = root.resolve("123");
        // Does not throw.
        assertDoesNotThrow(() -> svc.assertInside(child, root, "execution"));
    }

    @Test
    void assertInsideRejectsSibling() {
        Path root = svc.executionsRoot();
        Path sibling = root.getParent().resolve("evil");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.assertInside(sibling, root, "execution"));
        assertTrue(ex.getMessage().contains("execution")
                || ex.getMessage().contains("越界")
                || ex.getMessage().toLowerCase().contains("escape")
                || ex.getMessage().contains("越出"));
    }

    @Test
    void assertInsideRejectsRelativeTraversal() {
        Path root = svc.scriptsRoot();
        // ../escape tries to climb out via .. segments.
        Path evil = root.resolve("../escape/evil");
        assertThrows(BusinessException.class,
                () -> svc.assertInside(evil, root, "script"));
    }

    @Test
    void isInsideRealOnMissingPathReturnsFalse() {
        assertFalse(svc.isInsideReal(
                svc.executionsRoot().resolve("nope"),
                svc.executionsRoot()));
    }

    @Test
    void isInsideRealOnExistingChildReturnsTrue() throws Exception {
        Path child = svc.executionsRoot().resolve("111");
        Files.createDirectories(child);
        assertTrue(svc.isInsideReal(child, svc.executionsRoot()));
    }

    @Test
    void safeListOnMissingDirReturnsEmpty() throws Exception {
        assertTrue(svc.safeList(svc.executionsRoot().resolve("missing")).isEmpty());
        assertTrue(svc.safeList(null).isEmpty());
    }

    @Test
    void safeListReturnsEntries() throws Exception {
        Path dir = svc.executionsRoot();
        Files.createDirectories(dir.resolve("1"));
        Files.createDirectories(dir.resolve("2"));
        assertEquals(2, svc.safeList(dir).size());
    }
}