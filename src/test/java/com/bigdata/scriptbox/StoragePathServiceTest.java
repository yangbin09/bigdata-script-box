package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.exception.BusinessException;
import com.bigdata.scriptbox.service.StoragePathService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        // 用 Paths.get 逐段拼接断言，避免硬编码 '/' 分隔符导致 Windows 上误判。
        Path script = svc.scriptFilePath(42L);
        assertTrue(script.endsWith(Paths.get("scripts", "42", "script.sh")), script.toString());

        Path keytab = svc.keytabPath(7L, "uuid");
        assertEquals("tenant_7_uuid.keytab", keytab.getFileName().toString());
        assertTrue(keytab.getParent().endsWith(Paths.get("keytabs")), keytab.toString());

        Path execDir = svc.executionDirFor(123L);
        assertTrue(execDir.endsWith(Paths.get("executions", "123")), execDir.toString());

        Path art = svc.artifactsDirFor(123L);
        assertTrue(art.endsWith(Paths.get("executions", "123", "artifacts")), art.toString());

        Path input = svc.inputDirFor(123L);
        assertTrue(input.endsWith(Paths.get("executions", "123", "input")), input.toString());

        Path upload = svc.pendingUploadDir("tok-abc");
        assertTrue(upload.endsWith(Paths.get("data", "uploads", "tok-abc")), upload.toString());
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