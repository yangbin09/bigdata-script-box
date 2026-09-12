package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.model.ExecutionStatus;
import com.bigdata.scriptbox.service.ExecutionGate;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 取消语义的集成测试。
 *
 * <p>历史版本针对 {@code RunningExecutionRegistry.RunningExecution}（public 字段 +
 * {@code Process} 句柄直出）。现在取消状态与进程句柄由 {@link ExecutionGate.Permit}
 * 统一承担，本测试相应改为通过 gate 的访问器观察状态。
 */
class ExecutionCancellationTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private ScriptExecutor executor;
    @Autowired private ExecutionGate executionGate;

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

    /**
     * Spin until the execution shows up in the gate.
     *
     * <p>注意不要把状态卡死在 {@code RUNNING}：许可先以 {@code RESERVED} 发放（槽位已占、
     * 命令还在构造），随后 {@code pb.start()} 才把状态推进到 {@code RUNNING}。这里等
     * "已在闸门里且未被取消"即可，对取消语义而言两者等价。
     */
    private ExecutionGate.Permit waitUntilRunning(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (ExecutionGate.Permit p : executionGate.activePermits()) {
                if (!p.finished() && !p.cancelled()) return p;
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
            ExecutionGate.Permit live = waitUntilRunning(5000);

            boolean ok = executionGate.cancel(live.executionId());
            assertTrue(ok, "cancel should return true for a running execution");

            ExecutionHistory h = future.get(10, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.CANCELLED.name(), h.getStatus(), "status must be CANCELLED");
            assertEquals(-1, h.getExitCode(), "exit code should be sentinel -1 on cancel");
            assertFalse(h.getSuccess(), "success flag must be false on cancel");
            assertNull(executionGate.get(live.executionId()),
                    "gate entry must be cleared after cancellation");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelOnUnknownExecutionReturnsFalse() {
        // Picking a wildly-large id that no execution could have.
        assertFalse(executionGate.cancel(999_999_999L));
    }

    @Test
    void cancelTwiceIsIdempotent() throws Exception {
        Long sid = seedLongRunningScript("cancel-twice", LONG_RUNNING_BODY);
        Long tid = seedTenant();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ExecutionHistory> future = pool.submit(() -> executor.execute(request(sid, tid)));
            ExecutionGate.Permit live = waitUntilRunning(5000);

            assertTrue(executionGate.cancel(live.executionId()));
            assertFalse(executionGate.cancel(live.executionId()),
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
            ExecutionGate.Permit live = waitUntilRunning(5000);

            // Sleep long enough for the child process to be spawned.
            Thread.sleep(800);

            boolean ok = executionGate.cancel(live.executionId());
            assertTrue(ok);

            ExecutionHistory h = future.get(10, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.CANCELLED.name(), h.getStatus());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void activeCountReflectsRunningExecutions() throws Exception {
        Long sid = seedLongRunningScript("cancel-count", LONG_RUNNING_BODY);
        Long tid = seedTenant();

        int before = executionGate.activeCount();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ExecutionHistory> future = pool.submit(() -> executor.execute(request(sid, tid)));
            ExecutionGate.Permit live = waitUntilRunning(5000);
            assertEquals(before + 1, executionGate.activeCount());

            executionGate.cancel(live.executionId());
            future.get(10, TimeUnit.SECONDS);
            // 许可 close 之后，在途计数回到基线。
            assertEquals(before, executionGate.activeCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void permitMarksFinishedAfterExecution() throws Exception {
        Long sid = seedLongRunningScript("cancel-finished-flag", LONG_RUNNING_BODY);
        Long tid = seedTenant();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ExecutionHistory> future = pool.submit(() -> executor.execute(request(sid, tid)));
            ExecutionGate.Permit live = waitUntilRunning(5000);
            assertFalse(live.finished());

            // Cancel first so the executor thread can finish and close its permit.
            assertTrue(executionGate.cancel(live.executionId()));

            ExecutionHistory h = future.get(10, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.CANCELLED.name(), h.getStatus());
            // 许可已经 close：finished 置位、从表中移除。
            assertTrue(live.finished(), "permit must be marked finished once closed");
            assertNull(executionGate.get(live.executionId()));
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
            ExecutionGate.Permit live = waitUntilRunning(5000);
            executionGate.cancel(live.executionId());

            ExecutionHistory h = future.get(10, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.CANCELLED.name(), h.getStatus());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void gateSurvivesAcrossCancels() throws Exception {
        // Run + cancel + run + cancel in sequence. Each should not leak permits.
        Long sid = seedLongRunningScript("cancel-stress", LONG_RUNNING_BODY);
        Long tid = seedTenant();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            int before = executionGate.activeCount();
            for (int i = 0; i < 3; i++) {
                Future<ExecutionHistory> f = pool.submit(() -> executor.execute(request(sid, tid)));
                ExecutionGate.Permit live = waitUntilRunning(5000);
                executionGate.cancel(live.executionId());
                f.get(10, TimeUnit.SECONDS);
            }
            assertEquals(before, executionGate.activeCount(),
                    "gate must drain back to its baseline");
            assertEquals(0, executionGate.occupied(), "no slot may stay occupied");
        } finally {
            pool.shutdownNow();
        }
    }
}
