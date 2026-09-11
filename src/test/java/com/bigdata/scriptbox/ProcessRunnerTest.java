package com.bigdata.scriptbox;

import com.bigdata.scriptbox.executor.ProcessRequest;
import com.bigdata.scriptbox.executor.ProcessResult;
import com.bigdata.scriptbox.executor.ProcessRunner;
import com.bigdata.scriptbox.service.RunningExecutionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProcessRunner}.
 *
 * <p>Covers the three lifecycle paths the runner exposes:
 * <ol>
 *   <li>happy path — process exits 0, exit code is propagated.</li>
 *   <li>non-zero exit — ProcessResult reports exit code, no timeout/cancel flag.</li>
 *   <li>timeout — {@link Process#waitFor(long, TimeUnit)} triggers
 *       {@code destroyForcibly}, ProcessResult.timeout=true.</li>
 *   <li>cancel — {@link RunningExecutionRegistry#cancel} flips the cancelled
 *       flag, ProcessResult.cancelled=true.</li>
 * </ol>
 */
class ProcessRunnerTest {

    @TempDir Path tmp;
    private ProcessRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ProcessRunner(new RunningExecutionRegistry());
    }

    private ProcessRequest req(List<String> cmd, int timeoutSeconds) throws Exception {
        Path stdout = tmp.resolve("stdout.log");
        Path stderr = tmp.resolve("stderr.log");
        return new ProcessRequest(cmd, tmp, Map.of(), stdout, stderr, timeoutSeconds,
                "test", 1L, 1L);
    }

    @Test
    void happyPathExitZero() throws Exception {
        ProcessResult r = runner.run(1L, req(List.of("bash", "-c", "echo hello"), 5));
        assertTrue(r.ok());
        assertEquals(0, r.exitCode());
        assertFalse(r.timeout());
        assertFalse(r.cancelled());
        assertTrue(Files.readString(r.stdoutPath()).contains("hello"));
    }

    @Test
    void nonZeroExitReported() throws Exception {
        ProcessResult r = runner.run(2L, req(List.of("bash", "-c", "exit 7"), 5));
        assertFalse(r.ok());
        assertEquals(7, r.exitCode());
        assertFalse(r.timeout());
        assertFalse(r.cancelled());
    }

    @Test
    void timeoutTriggersDestroy() throws Exception {
        // sleep for 5 seconds, timeout in 1 → destroy + timeout=true
        ProcessResult r = runner.run(3L, req(List.of("bash", "-c", "sleep 5; exit 0"), 1));
        assertTrue(r.timeout());
        assertFalse(r.ok());
        assertFalse(r.cancelled());
    }

    @Test
    void cancelFlipsRegistryFlag() throws Exception {
        // Pre-register so we can cancel via the registry before the process
        // naturally exits. The runner will reuse the registry handed to it
        // when calling register() internally — we need a wrapper here.
        // Instead, use the runner's own registry by cancelling via the registry
        // it was constructed with (which is a separate instance).
        RunningExecutionRegistry shared = new RunningExecutionRegistry();
        ProcessRunner r = new ProcessRunner(shared);
        ProcessRequest request = req(List.of("bash", "-c", "sleep 30; exit 0"), 60);
        // Spawn a thread that will cancel after ProcessRunner registers.
        Thread canceller = new Thread(() -> {
            try {
                // Wait up to 5s for the runner to register.
                long deadline = System.currentTimeMillis() + 5000;
                while (shared.activeCount() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                shared.cancel(99L);
            } catch (InterruptedException ignored) {}
        });
        canceller.start();
        ProcessResult result = r.run(99L, request);
        canceller.join();
        assertTrue(result.cancelled() || result.timeout(),
                "expected cancelled=true or timeout=true after cancel; got " + result);
    }

    @Test
    void stdoutAndStderrAreKeptSeparate() throws Exception {
        ProcessResult r = runner.run(4L, req(List.of("bash", "-c",
                "echo to-stdout; echo to-stderr >&2"), 5));
        assertTrue(r.ok());
        assertTrue(Files.readString(r.stdoutPath()).contains("to-stdout"));
        assertTrue(Files.readString(r.stderrPath()).contains("to-stderr"));
        assertFalse(Files.readString(r.stdoutPath()).contains("to-stderr"),
                "stderr must not leak into stdout file");
    }
}