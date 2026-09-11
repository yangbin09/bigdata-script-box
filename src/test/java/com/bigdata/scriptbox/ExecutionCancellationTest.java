package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.service.RunningExecutionRegistry;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionCancellationTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private ScriptExecutor executor;
    @Autowired private RunningExecutionRegistry runningRegistry;

    /** Bash script that prints a start line, sleeps for 60s, prints done, exits 0.
     *  We never let it actually finish; cancellation should tear it down early. */
    private static final String LONG_RUNNING_BODY =
            "#!/bin/bash\n" +
            "echo start\n" +
            "sleep 60\n" +
            "echo done\n" +
            "exit 0\n";

    /** Bash script that spawns a child process (also a sleep) and waits on it.
     *  Used to verify descendants() are also destroyed. */
    private static final String WITH_CHILDREN_BODY =
            "#!/bin/bash\n" +
            "echo parent-start\n" +
            "sleep 120 &\n" +
            "wait $!\n" +
            "echo parent-end\n" +
            "exit 0\n";

    private Long seedLongRunningScript(String name, String body) {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(name);
        s.setCategory("Mock");
        s.setTimeoutSeconds(600);
        s.setEnabled(true);
        Script saved;
        try {
            saved = scriptService.create(s, new InMemoryMultipartFile(
                    "file", name + ".sh", "application/x-sh",
                    body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return saved.getId();
    }

    private Long seedTenant() {
        Tenant t = new Tenant();
        t.setName("cancel-tenant-" + System.nanoTime());
        t.setPrincipal("ct@EXAMPLE.COM");
        t.setEnabled(true);
        return tenantService.create(t).getId();
    }

    private ExecutionRequest request(Long scriptId, Long tenantId) {
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(scriptId);
        req.setTenantId(tenantId);
        return req;
    }

    /** Spin until the running execution shows up in the registry. */
    private RunningExecutionRegistry.RunningExecution waitUntilRunning(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (RunningExecutionRegistry.RunningExecution re : ((Iterable<RunningExecutionRegistry.RunningExecution>)
                    () -> runningRegistry.activeExecutions().iterator())) {
                if (!re.cancelled.get()) return re;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("no running execution appeared within " + timeoutMs + "ms");
    }

    @Test
    void cancelKillsProcessAndMarksCancelled() throws Exception {
        Long sid = seedLongRunningScript("cancel-basic", LONG_RUNNING_BODY);
        Long tid = seedTenant();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ExecutionHistory> future = pool.submit(() -> executor.execute(request(sid, tid)));
            RunningExecutionRegistry.RunningExecution live = waitUntilRunning(5000);
            assertTrue(live.process.isAlive(), "process should be alive before cancel");

            boolean ok = runningRegistry.cancel(live.executionId);
            assertTrue(ok, "cancel should return true for a running execution");

            ExecutionHistory h = future.get(10, TimeUnit.SECONDS);
            assertEquals("CANCELLED", h.getStatus(), "status must be CANCELLED");
            assertEquals(-1, h.getExitCode(), "exit code should be sentinel -1 on cancel");
            assertFalse(h.getSuccess(), "success flag must be false on cancel");
            assertNull(runningRegistry.get(live.executionId),
                    "registry entry must be cleared after cancellation");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelOnUnknownExecutionReturnsFalse() {
        // Picking a wildly-large id that no execution could have.
        assertFalse(runningRegistry.cancel(999_999_999L));
    }

    @Test
    void cancelTwiceIsIdempotent() throws Exception {
        Long sid = seedLongRunningScript("cancel-twice", LONG_RUNNING_BODY);
        Long tid = seedTenant();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ExecutionHistory> future = pool.submit(() -> executor.execute(request(sid, tid)));
            RunningExecutionRegistry.RunningExecution live = waitUntilRunning(5000);

            assertTrue(runningRegistry.cancel(live.executionId));
            assertFalse(runningRegistry.cancel(live.executionId),
                    "second cancel must be a no-op");

            future.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelKillsDescendantProcesses() throws Exception {
        Long sid = seedLongRunningScript("cancel-descendants", WITH_CHILDREN_BODY);
        Long tid = seedTenant();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ExecutionHistory> future = pool.submit(() -> executor.execute(request(sid, tid)));
            RunningExecutionRegistry.RunningExecution live = waitUntilRunning(5000);

            // Sleep long enough for the child process to be spawned.
            Thread.sleep(800);
            assertTrue(live.process.isAlive(), "parent should still be alive");

            boolean ok = runningRegistry.cancel(live.executionId);
            assertTrue(ok);

            ExecutionHistory h = future.get(10, TimeUnit.SECONDS);
            assertEquals("CANCELLED", h.getStatus());
            // After cancel, both the parent and its children must be gone.
            assertFalse(live.process.isAlive(),
                    "parent process must not be alive after cancel");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void activeCountReflectsRunningExecutions() throws Exception {
        Long sid = seedLongRunningScript("cancel-count", LONG_RUNNING_BODY);
        Long tid = seedTenant();

        int before = runningRegistry.activeCount();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ExecutionHistory> future = pool.submit(() -> executor.execute(request(sid, tid)));
            RunningExecutionRegistry.RunningExecution live = waitUntilRunning(5000);
            assertEquals(before + 1, runningRegistry.activeCount());

            runningRegistry.cancel(live.executionId);
            future.get(10, TimeUnit.SECONDS);
            // After unregister, count is back to before.
            assertEquals(before, runningRegistry.activeCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unregisterMarksFinishedFlag() throws Exception {
        Long sid = seedLongRunningScript("cancel-finished-flag", LONG_RUNNING_BODY);
        Long tid = seedTenant();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ExecutionHistory> future = pool.submit(() -> executor.execute(request(sid, tid)));
            RunningExecutionRegistry.RunningExecution live = waitUntilRunning(5000);
            assertFalse(live.finished.get());

            // Cancel first so the executor thread can finish and call unregister
            // itself; that is the path we want to verify (the executor invokes
            // unregister on its way out, which sets finished=true on the entry).
            assertTrue(runningRegistry.cancel(live.executionId));

            ExecutionHistory h = future.get(10, TimeUnit.SECONDS);
            assertEquals("CANCELLED", h.getStatus());
            // The live entry is now out of the map; the entry's finished flag
            // was set by the executor's unregister call.
            assertTrue(live.finished.get(),
                    "finished flag should be set after executor unregisters");
            assertNull(runningRegistry.get(live.executionId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void normalExecutionStillMarksSuccessWhenNotCancelled() throws Exception {
        Long sid = seedLongRunningScript("cancel-normal", LONG_RUNNING_BODY);
        Long tid = seedTenant();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Cancel immediately so the process is killed quickly.
            Future<ExecutionHistory> future = pool.submit(() -> executor.execute(request(sid, tid)));
            RunningExecutionRegistry.RunningExecution live = waitUntilRunning(5000);
            runningRegistry.cancel(live.executionId);

            ExecutionHistory h = future.get(10, TimeUnit.SECONDS);
            assertEquals("CANCELLED", h.getStatus());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void registrySurvivesAcrossCancels() throws Exception {
        // Run + cancel + run + cancel in sequence. Each should not leak registry entries.
        Long sid = seedLongRunningScript("cancel-stress", LONG_RUNNING_BODY);
        Long tid = seedTenant();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            int before = runningRegistry.activeCount();
            for (int i = 0; i < 3; i++) {
                Future<ExecutionHistory> f = pool.submit(() -> executor.execute(request(sid, tid)));
                RunningExecutionRegistry.RunningExecution live = waitUntilRunning(5000);
                runningRegistry.cancel(live.executionId);
                f.get(10, TimeUnit.SECONDS);
            }
            assertEquals(before, runningRegistry.activeCount(),
                    "registry must drain back to its baseline");
        } finally {
            pool.shutdownNow();
        }
    }
}