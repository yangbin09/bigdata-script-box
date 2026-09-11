package com.bigdata.scriptbox;

import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ExecutionContext}.
 *
 * <p>No Spring context required — the record is plain Java. Validates:
 * <ol>
 *   <li>Defensive copy of the {@code params} map (mutating the original after
 *       construction must not affect the record).</li>
 *   <li>{@code null} params becomes {@link Map#of()} (empty immutable map).</li>
 *   <li>Builder copies values through faithfully.</li>
 * </ol>
 */
class ExecutionContextTest {

    @Test
    void paramsAreDefensivelyCopied() {
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("a", "1");
        mutable.put("b", "2");

        ExecutionContext ctx = ExecutionContext.builder()
                .executionId(1L)
                .script(new Script())
                .tenant(new Tenant())
                .request(new ExecutionRequest())
                .params(mutable)
                .build();

        // Mutate the original map — the record must NOT see the change.
        mutable.put("c", "3");
        mutable.remove("a");

        assertEquals(2, ctx.params().size(), "record params must be a defensive copy");
        assertTrue(ctx.params().containsKey("a"));
        assertTrue(ctx.params().containsKey("b"));
        assertFalse(ctx.params().containsKey("c"));
    }

    @Test
    void nullParamsBecomesEmptyMap() {
        ExecutionContext ctx = ExecutionContext.builder()
                .executionId(2L)
                .script(new Script())
                .tenant(new Tenant())
                .request(new ExecutionRequest())
                .params(null)
                .build();

        assertNotNull(ctx.params());
        assertTrue(ctx.params().isEmpty());
    }

    @Test
    void builderCarriesPathsThrough() {
        Path execDir = Paths.get("/tmp/x/exec");
        Path artDir = Paths.get("/tmp/x/exec/artifacts");
        ExecutionContext ctx = ExecutionContext.builder()
                .executionId(42L)
                .script(new Script())
                .tenant(new Tenant())
                .request(new ExecutionRequest())
                .executionDir(execDir)
                .artifactDir(artDir)
                .timeoutSeconds(120)
                .kinitWrapped(true)
                .build();

        assertEquals(42L, ctx.executionId());
        assertEquals(execDir, ctx.executionDir());
        assertEquals(artDir, ctx.artifactDir());
        assertEquals(120, ctx.timeoutSeconds());
        assertTrue(ctx.kinitWrapped());
    }

    @Test
    void allFieldsAreImmutable() {
        // Record + defensive Map.copyOf → cannot mutate after construction.
        Map<String, String> backing = new HashMap<>();
        backing.put("k", "v");
        ExecutionContext ctx = ExecutionContext.builder()
                .executionId(1L)
                .script(new Script())
                .tenant(new Tenant())
                .request(new ExecutionRequest())
                .params(backing)
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> ctx.params().put("z", "z"),
                "record.params() should be an unmodifiable view");
    }
}