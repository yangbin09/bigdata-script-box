package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.exception.BusinessException;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.mapper.ScriptMapper;
import com.bigdata.scriptbox.service.ExecutionGate;
import com.bigdata.scriptbox.service.ExecutionRunner;
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
    @Autowired private ExecutionRunner executionRunner;
    @Autowired private ExecutionHistoryMapper historyMapper;

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
            BusinessException ex = assertThrows(BusinessException.class,
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
                BusinessException ex = assertThrows(BusinessException.class,
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

    /**
     * 异步路径下「准入被拒」必须把已存在的 RUNNING 行推进到终态，而不是留在 RUNNING。
     *
     * <p>回归背景：{@code ExecutionRunner.submit()} 先分配 executionId 并立刻返回，
     * 真正的 {@code ExecutionGate} 准入发生在 worker 线程里。当一个执行快结束时
     * 用户又点了一次执行，会出现这个组合：
     * <ol>
     *   <li>新的 executionId 已经被 {@code captureSnapshot} 写成了 RUNNING 行；</li>
     *   <li>worker 稍后在准入阶段被拒（另一个执行还占着槽位）；</li>
     *   <li>兜底逻辑用同一个 id 再 insert 一次 → 撞主键、异常被吞 → 这一行
     *       <b>永远停在 RUNNING</b>，任务中心一直转圈，只能等 11 分钟超时收场。</li>
     * </ol>
     *
     * <p>要确定性地构造这个状态，必须知道 worker 会用的那个 ID。{@code submit()} 会
     * 覆盖调用方传入的 executionId（用内部 asyncCounter 重新分配），所以这里通过
     * 反射把计数器钉到一个已知值，从而能提前把 RUNNING 行插进去。
     */
    @Test
    void asyncRejectedDuplicateDoesNotLeaveRowRunning() throws Exception {
        Long sid = seedScript("async-dup", SLEEP_BODY, false);
        Long tid = seedTenant();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        // 捕获 ExecutionRunner 的 ERROR 日志：撞主键后的降级路径会打 ERROR，
        // 正常路径（先查再写）不会。用它区分"判断正确"与"碰巧被兜住"。
        java.util.List<String> mapperErrors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        ch.qos.logback.classic.Logger runnerLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                        .getLogger(com.bigdata.scriptbox.service.ExecutionRunner.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        runnerLogger.addAppender(appender);
        try {
            // 先占住槽位：一个正在跑的 sleep 执行
            Future<ExecutionHistory> holder = pool.submit(() -> executor.execute(request(sid, tid)));
            waitForScript(sid, 10_000);

            // 把 asyncCounter 钉到已知值 → 下一次 submit 必然得到 pinned
            long pinned = 700_000_002L;
            java.lang.reflect.Field counterField =
                    com.bigdata.scriptbox.service.ExecutionRunner.class
                            .getDeclaredField("asyncCounter");
            counterField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicLong counter =
                    (java.util.concurrent.atomic.AtomicLong) counterField.get(executionRunner);
            long originalCounter = counter.get();
            counter.set(pinned - 1);

            // 构造步骤 1：captureSnapshot 会写的 RUNNING 行提前放好
            ExecutionHistory pre = new ExecutionHistory();
            pre.setId(pinned);
            pre.setScriptId(sid);
            pre.setTenantId(tid);
            pre.setParametersJson("{}");
            pre.setStatus("RUNNING");
            pre.setSuccess(false);
            pre.setTimeout(false);
            pre.setStartTime(java.time.LocalDateTime.now());
            pre.setDurationMs(0L);
            historyMapper.insert(pre);
            assertEquals("RUNNING", historyMapper.selectById(pinned).getStatus(), "前置条件");

            try {
                // 步骤 2 + 3：同脚本二次执行 → 准入被拒 → 走 writeFailedHistory
                executionRunner.submit(request(sid, tid));
            } finally {
                counter.set(originalCounter);
            }

            // 不变式：那一行必须进入终态，且不能被当成成功
            long deadline = System.currentTimeMillis() + 15_000;
            ExecutionHistory after = null;
            while (System.currentTimeMillis() < deadline) {
                after = historyMapper.selectById(pinned);
                if (after != null && !"RUNNING".equals(after.getStatus())
                        && !"PENDING".equals(after.getStatus())) {
                    break;
                }
                Thread.sleep(100);
            }
            assertNotNull(after);
            for (ch.qos.logback.classic.spi.ILoggingEvent e : appender.list) {
                // 只看"写库失败"这一类告警；"任务异常"本身是这条用例的预期现象
                if (e.getLevel() == ch.qos.logback.classic.Level.ERROR
                        && e.getFormattedMessage().contains("写")) {
                    mapperErrors.add(e.getFormattedMessage());
                }
            }
            assertNotEquals("RUNNING", after.getStatus(),
                    "被拒的执行不能永远停在 RUNNING（这正是任务中心一直转圈的原因）");
            assertFalse(Boolean.TRUE.equals(after.getSuccess()), "被拒的执行不应显示为成功");
            assertNotNull(after.getInterruptedReason(), "应记录被拒原因，便于排查");
            // 关键：必须走"存在则更新"的正常分支，而不是撞主键后再降级。
            // 降级虽然也能把状态推进到终态，但它意味着我们对"行已存在"这件事
            // 没有判断，只是碰巧被 catch 兜住了 —— 那正是原始 bug 的形态。
            assertTrue(mapperErrors.isEmpty(),
                    "不该出现写库失败告警（说明撞了主键走了降级路径）：" + mapperErrors);

            ExecutionGate.Permit live = waitForScript(sid, 15_000);
            executionGate.cancel(live.executionId());
            holder.get(20, TimeUnit.SECONDS);
        } finally {
            runnerLogger.detachAppender(appender);
            pool.shutdownNow();
        }
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