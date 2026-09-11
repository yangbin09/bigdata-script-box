package com.bigdata.scriptbox;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Base for integration tests. Uses the same ./data/ directory as production so that we
 * exercise the real H2 file path. Each test cleans up before running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "scriptbox.data-dir=./target/test-data",
        "scriptbox.scripts-dir=./target/test-data/scripts",
        "scriptbox.keytabs-dir=./target/test-data/keytabs",
        "scriptbox.executions-dir=./target/test-data/executions",
        "spring.datasource.url=jdbc:h2:file:./target/test-db/scriptbox;DB_CLOSE_ON_EXIT=FALSE",
        "spring.sql.init.mode=always"
})
public abstract class BaseIntegrationTest {

    @Autowired
    protected com.bigdata.scriptbox.mapper.TenantMapper tenantMapper;

    @Autowired
    protected com.bigdata.scriptbox.mapper.ScriptMapper scriptMapper;

    @Autowired
    protected com.bigdata.scriptbox.mapper.ScriptParamMapper scriptParamMapper;

    @Autowired
    protected com.bigdata.scriptbox.mapper.ExecutionHistoryMapper historyMapper;

    @BeforeEach
    void clean() throws Exception {
        tenantMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers.emptyWrapper());
        scriptMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers.emptyWrapper());
        scriptParamMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers.emptyWrapper());
        historyMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers.emptyWrapper());
        // also wipe script files on disk
        Path scriptsDir = Paths.get("./target/test-data/scripts");
        if (Files.exists(scriptsDir)) {
            try (var stream = Files.list(scriptsDir)) {
                stream.forEach(p -> {
                    try { if (Files.isDirectory(p)) Files.walk(p).sorted(java.util.Comparator.reverseOrder())
                            .forEach(f -> { try { Files.deleteIfExists(f); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
                });
            }
        }
        Path execs = Paths.get("./target/test-data/executions");
        if (Files.exists(execs)) {
            Files.walk(execs).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }
}