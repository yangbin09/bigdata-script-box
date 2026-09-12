package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.mapper.ScriptMapper;
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

class ConcurrencyGateTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private ScriptExecutor executor;
    @Autowired private ScriptMapper scriptMapper;
    @Autowired private ExecutionGate executionGate;
    @Autowired private ScriptBoxProperties props;

    private static final String SLEEP_BODY =
            "#!/bin/bash\n" +
            "echo start\n" +
            "sleep 30\n" +
            "echo done\n" +
            "exit 0\n";

    private static final String FAST_BODY =
            "#!/bin/bash\necho ok\nexit 0\n";

    private Long seedScript(String name, String body, Boolean allowConcurrent) {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(name);
        s.setCategory("Mock");
        s.setTimeoutSeconds(120);
        s.setEnabled(true);
        Script saved;
        try {
            saved = scriptService.create(s, new InMemoryMultipartFile(
                    "file", name + ".sh", "application/x-sh",
                    body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (allowConcurrent != null) {
            saved.setAllowConcurrent(allowConcurrent);
            saved.setUpdateTime(java.time.LocalDateTime.now());
            scriptMapper.updateById(saved);
        }
        return saved.getId();
    }

    private Long seedTenant() {
        Tenant t = new Tenant();
        t.setName("conc-tenant-" + System.nanoTime());
        t.setPrincipal("conct@EXAMPLE.COM");
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
     * Wait until a permit for this script shows up in the gate.
     *
     * <p>不要求状态已经是 {@code RUNNING}：许可先以 {@code RESERVED} 发放（槽位已占、
     * 命令还在构造），{@code pb.start()} 之后才变 {@code RUNNING}。对并发语义而言
     * 两者都算"已经占住槽位"。
     */
    private ExecutionGate.Permit waitForScript(long scriptId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (ExecutionGate.Permit p : executionGate.activePermits()) {
                if (p.scriptId() == scriptId && !p.finished() && !p.cancelled()) {
                    return p;
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("no execution for scriptId=" + scriptId + " appeared in gate within " + timeoutMs + "ms");
    }

    @Test
    void duplicateRejectedWhenAllowConcurrentFalse() throws Exception {
        Long sid = seedScript("dup-disallow", SLEEP_BODY, false);
        Long tid = seedTenant();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ExecutionHistory> f1 = pool.submit(() -> executor.execute(request(sid, tid)));
            waitForScript(sid, 5000);

            // Second request for the same script must be refused while the
            // first is still running (allowConcurrent=false).
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> executor.execute(request(sid, tid)));
            assertTrue(ex.getMessage().contains("already running"),
                    "should mention duplicate-runner: " + ex.getMessage());

            // Clean up: cancel the running execution so the future resolves.
            ExecutionGate.Permit live = waitForScript(sid, 5000);
            executionGate.cancel(live.executionId());
            f1.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void duplicateAllowedWhenAllowConcurrentTrue() throws Exception {
        Long sid = seedScript("dup-allow", SLEEP_BODY, true);
        Long tid = seedTenant();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ExecutionHistory> f1 = pool.submit(() -> executor.execute(request(sid, tid)));
            Future<ExecutionHistory> f2 = pool.submit(() -> executor.execute(request(sid, tid)));

            // Both must start; we wait briefly for the gate to see both.
            Thread.sleep(500);
            int matching = 0;
            for (ExecutionGate.Permit p : executionGate.activePermits()) {
                if (p.scriptId() == sid) matching++;
            }
            assertEquals(2, matching, "both executions should be live concurrently");

            // Cancel both to release the futures.
            for (ExecutionGate.Permit p : executionGate.activePermits()) {
                if (p.scriptId() == sid) executionGate.cancel(p.executionId());
            }
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrencyCapEnforced() throws Exception {
        // Force the cap down to 1 for the test, then verify a second concurrent
        // run is refused with the expected message.
        int originalMax = props.getMaxConcurrent();
        props.setMaxConcurrent(1);
        try {
            Long sidA = seedScript("cap-a", SLEEP_BODY, true);
            Long sidB = seedScript("cap-b", SLEEP_BODY, true);
            Long tid = seedTenant();

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<ExecutionHistory> fA = pool.submit(() -> executor.execute(request(sidA, tid)));
                waitForScript(sidA, 5000);

                // The global cap is 1 — second execution (different script,
                // both allowConcurrent=true) must still be rejected.
                IllegalStateException ex = assertThrows(IllegalStateException.class,
                        () -> executor.execute(request(sidB, tid)));
                assertTrue(ex.getMessage().toLowerCase().contains("slot limit"),
                        "should mention slot limit: " + ex.getMessage());
                assertTrue(ex.getMessage().contains("max-concurrent=1"),
                        "should report the configured cap: " + ex.getMessage());

                ExecutionGate.Permit live = waitForScript(sidA, 5000);
                executionGate.cancel(live.executionId());
                fA.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        } finally {
            // Restore the property so other tests aren't affected.
            props.setMaxConcurrent(originalMax);
        }
    }

    @Test
    void slotReleasedAfterExecution() throws Exception {
        props.setMaxConcurrent(2);
        try {
            Long sid = seedScript("slot-release", FAST_BODY, true);
            Long tid = seedTenant();
            int before = executionGate.activeCount();
            int semBefore = availableSlots();

            ExecutionHistory h = executor.execute(request(sid, tid));
            assertTrue(h.getSuccess());
            // After completion, the gate is empty AND the slot count is back to full.
            assertEquals(before, executionGate.activeCount());
            assertEquals(semBefore, availableSlots());
        } finally {
            props.setMaxConcurrent(5);
        }
    }

    @Test
    void slotReleasedAfterCancel() throws Exception {
        props.setMaxConcurrent(2);
        try {
            Long sid = seedScript("slot-cancel", SLEEP_BODY, true);
            Long tid = seedTenant();
            int semBefore = availableSlots();

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<ExecutionHistory> f = pool.submit(() -> executor.execute(request(sid, tid)));
                ExecutionGate.Permit live = waitForScript(sid, 5000);
                assertEquals(semBefore - 1, availableSlots(),
                        "slot should be held while running");

                executionGate.cancel(live.executionId());
                f.get(10, TimeUnit.SECONDS);
                assertEquals(semBefore, availableSlots(),
                        "slot must be released after cancel+finish");
            } finally {
                pool.shutdownNow();
            }
        } finally {
            props.setMaxConcurrent(5);
        }
    }

    @Test
    void allowedSlotsMatchesMaxConcurrentConfig() {
        int cap = props.getMaxConcurrent();
        assertTrue(cap >= 1, "maxConcurrent should be at least 1, got " + cap);
        assertEquals(cap, availableSlots(),
                "available slots must equal the configured cap on startup");
    }

    @Test
    void setMaxConcurrentNormalisesBelowOne() {
        int original = props.getMaxConcurrent();
        try {
            props.setMaxConcurrent(0);
            assertEquals(1, props.getMaxConcurrent(),
                    "maxConcurrent below 1 must be clamped to 1");
            props.setMaxConcurrent(-3);
            assertEquals(1, props.getMaxConcurrent(),
                    "negative maxConcurrent must be clamped to 1");
        } finally {
            props.setMaxConcurrent(original);
        }
    }

    /** Number of available slots, straight from the gate's own accounting. */
    private int availableSlots() {
        return executionGate.availableSlots();
    }

    /** Cheap helper: round-trip many sequential executions and assert no
     *  leaks (slot count always returns to baseline). */
    @Test
    void manyExecutionsDontLeakSlots() throws Exception {
        int originalMax = props.getMaxConcurrent();
        props.setMaxConcurrent(3);
        try {
            Long sid = seedScript("slot-leak", FAST_BODY, true);
            Long tid = seedTenant();
            int semBefore = availableSlots();
            for (int i = 0; i < 30; i++) {
                ExecutionHistory h = executor.execute(request(sid, tid));
                assertTrue(h.getSuccess(), "iteration " + i + " failed: " + h.getStatus());
            }
            assertEquals(semBefore, availableSlots(),
                    "slot count must return to baseline after many executions");
        } finally {
            props.setMaxConcurrent(originalMax);
        }
    }
}