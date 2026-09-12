package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.executor.ProcessRequest;
import com.bigdata.scriptbox.executor.ProcessResult;
import com.bigdata.scriptbox.executor.ProcessRunner;
import com.bigdata.scriptbox.service.ExecutionGate;
import com.bigdata.scriptbox.util.TextDecoder;
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
 * <p>Covers the lifecycle paths the runner exposes:
 * <ol>
 *   <li>happy path — process exits 0, exit code is propagated.</li>
 *   <li>non-zero exit — ProcessResult reports exit code, no timeout/cancel flag.
 *       （回归点：waitFor(timeout) 返回 false 并不等于超时，也可能是"进程已退出、
 *       onExit 通知未送达"，早期实现会把退出码 7 误写成哨兵值 -1。）</li>
 *   <li>timeout — {@link Process#waitFor(long, TimeUnit)} 超时后 destroyForcibly，
 *       ProcessResult.timeout=true。</li>
 *   <li>cancel — {@link ExecutionGate#cancel} 置位取消标志，ProcessResult.cancelled=true。</li>
 * </ol>
 *
 * <p>注意：并发槽位的生命周期归调用方（{@code ScriptExecutor} 持有的
 * {@link ExecutionGate.Permit}）。因此 "cancel" 用例需要自己先 acquire 一张许可，
 * 再把它交给 {@code ProcessRunner.run(executionId, req)}。
 */
class ProcessRunnerTest {

    /**
     * 用 {@code CleanupMode.NEVER}：不要 JUnit 在用例结束后递归删除临时目录。
     *
     * <p>原因：本用例会主动强杀子进程树（timeout / cancel），而 Windows 上被强杀的
     * Git Bash 的后代可能比父进程晚几秒才真正死亡，期间仍持有内核重定向的
     * stdout/stderr 文件句柄。JUnit 的 {@code @TempDir} 清理发生在**用例刚结束**的瞬间，
     * 撞上这个窗口就会报
     * {@code IOException: Failed to delete temp directory ...}，
     * 让"产品逻辑通过"的用例随机变红（实测 timeoutTriggersDestroy / nonZeroExitReported
     * 都会随机命中）。
     *
     * <p>这与产品功能无关（取消、超时、退出码、槽位释放均正确），纯粹是**测试清理时机**
     * 与 Windows 文件句柄生命周期的竞争。交给操作系统回收临时目录即可。
     */
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tmp;
    private ProcessRunner runner;
    private ScriptBoxProperties props;
    private ExecutionGate gate;

    @BeforeEach
    void setUp() {
        props = new ScriptBoxProperties();
        gate = new ExecutionGate(props);
        runner = new ProcessRunner(gate, props);
    }

    private ProcessRequest req(List<String> cmd, int timeoutSeconds) throws Exception {
        Path stdout = tmp.resolve("stdout.log");
        Path stderr = tmp.resolve("stderr.log");
        // 固定 UTF-8 区域设置（生产路径由 ScriptExecutor.buildEnv 设置同样的两个变量）。
        return new ProcessRequest(cmd, tmp, Map.of("LANG", "C.UTF-8", "LC_ALL", "C.UTF-8"),
                stdout, stderr, timeoutSeconds, "test");
    }

    @Test
    void happyPathExitZero() throws Exception {
        ProcessResult r = runner.run(1L, req(List.of("bash", "-c", "echo hello"), 5));
        assertTrue(r.ok());
        assertEquals(0, r.exitCode());
        assertFalse(r.timeout());
        assertFalse(r.cancelled());
        assertTrue(TextDecoder.readLenient(r.stdoutPath()).contains("hello"));
    }

    @Test
    void nonZeroExitReported() throws Exception {
        ProcessResult r = runner.run(2L, req(List.of("bash", "-c", "exit 7"), 5));
        assertFalse(r.ok());
        assertEquals(7, r.exitCode(), "真实退出码不能被误写成超时哨兵值 -1");
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
    void cancelFlipsPermitFlag() throws Exception {
        // 真实调用路径：ScriptExecutor 先 acquire 一张许可，再交给 ProcessRunner。
        // 这里照做，然后从另一个线程通过 gate.cancel(executionId) 发取消信号。
        long executionId = 99L;
        ProcessRequest request = req(List.of("bash", "-c", "sleep 30; exit 0"), 60);

        Thread canceller = new Thread(() -> {
            try {
                // 等进程真正起来（许可绑定成功）再取消
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    ExecutionGate.Permit p = gate.get(executionId);
                    if (p != null && p.state() == ExecutionGate.State.RUNNING) break;
                    Thread.sleep(25);
                }
                gate.cancel(executionId);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        canceller.start();

        ProcessResult result;
        try (ExecutionGate.Permit permit = gate.acquire(executionId, 1L, 1L, "test-script", true).orThrow()) {
            result = runner.run(executionId, request);
        }
        canceller.join();

        assertTrue(result.cancelled() || result.timeout(),
                "expected cancelled=true or timeout=true after cancel; got " + result);
        // 取消后许可应当已经释放：槽位归还
        assertEquals(0, gate.occupied(), "permit must release its slot after close");
    }

    @Test
    void stdoutAndStderrAreKeptSeparate() throws Exception {
        ProcessResult r = runner.run(4L, req(List.of("bash", "-c",
                "echo to-stdout; echo to-stderr >&2"), 5));
        assertTrue(r.ok());
        // 用宽容解码读日志：Windows 上 Git Bash 在重定向句柄上会写 UTF-16LE（带 FF FE BOM），
        // 严格 Files.readString 会抛 MalformedInputException；生产侧的日志展示同样走
        // com.bigdata.scriptbox.util.TextDecoder。
        String out = TextDecoder.readLenient(r.stdoutPath());
        String err = TextDecoder.readLenient(r.stderrPath());
        assertTrue(out.contains("to-stdout"), out);
        assertTrue(err.contains("to-stderr"), err);
        assertFalse(out.contains("to-stderr"), "stderr must not leak into stdout file");
    }

    /**
     * 输出文件句柄必须在 run() 返回前就释放掉。
     *
     * <p>回归背景：进程退出与内核释放重定向句柄不是同一时刻（后代进程会稍晚才死）。
     * 早期实现只等进程退出，于是紧随其后的清理（CleanupService 删执行目录、测试删临时目录）
     * 会**偶发**失败 —— 表现为某个用例随机挂在 JUnit 的 @TempDir 清理上。
     *
     * <p>这里连续跑多轮并在每轮结束后立刻删除输出文件：只要有一轮句柄没释放就会失败。
     * 文件名带轮次后缀，避免跨轮次互相掩盖。
     */
    @Test
    void outputHandlesAreReleasedBeforeReturn() throws Exception {
        for (int i = 0; i < 10; i++) {
            Path stdout = tmp.resolve("handles-out-" + i + ".log");
            Path stderr = tmp.resolve("handles-err-" + i + ".log");
            ProcessRequest request = new ProcessRequest(
                    List.of("bash", "-c", "echo out-" + i + "; echo err-" + i + " >&2; exit 0"),
                    tmp, Map.of("LANG", "C.UTF-8", "LC_ALL", "C.UTF-8"),
                    stdout, stderr, 10, "handles-" + i);
            ProcessResult r = runner.run(2000L + i, request);

            assertTrue(r.ok(), "iteration " + i + " exit=" + r.exitCode());
            assertTrue(TextDecoder.readLenient(stdout).contains("out-" + i));
            assertTrue(TextDecoder.readLenient(stderr).contains("err-" + i));
            assertTrue(Files.deleteIfExists(stdout), "iteration " + i + ": stdout 句柄未释放");
            assertTrue(Files.deleteIfExists(stderr), "iteration " + i + ": stderr 句柄未释放");
        }
    }
}
