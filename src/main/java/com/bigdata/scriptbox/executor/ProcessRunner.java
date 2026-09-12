package com.bigdata.scriptbox.executor;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.service.ExecutionGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 进程执行器 —— 全项目唯一的子进程启动实现（{@link CommandExecutor} 的生产实现）。
 *
 * <p>封装 ProcessBuilder 启动 + 超时 + 取消 + 输出落盘 + 退出码这些"通用样板"，
 * 让 {@link ScriptExecutor} 只关心业务参数，让 {@code SyntaxCheckService} /
 * {@code TenantService} 不必再自己写一套（历史上那两处都是无超时 {@code waitFor()}，
 * 会把请求线程永久挂起）。
 *
 * <p>关键设计点：
 * <ol>
 *   <li><b>输出只用内核重定向</b> —— 子进程直接写文件描述符
 *       （{@code ProcessBuilder.redirectOutput}）。不用应用侧读管道有三个原因：
 *       管道要有线程持续消费否则子进程写满缓冲就阻塞；进程被强杀后 Windows 上孙进程
 *       仍持有管道写端导致读取线程永远等不到 EOF；而且从另一个线程 close 一个正被
 *       read 的流在 Windows 上并不生效（{@code FileInputStream.close()} 要先抢同一个
 *       监视器，结果一起卡死）。内核重定向还顺带保证输出逐字节忠实 —— 旧实现用
 *       {@code readLine()+newLine()} 会把 {@code \r\n} 改成 {@code \n}、把 {@code \r}
 *       进度输出塌成一行。</li>
 *   <li><b>输出上限靠结束后截断</b> —— 进程运行期间可能短暂超过
 *       {@code scriptbox.max-output-bytes}（受 timeoutSeconds 约束），进程结束后立刻
 *       截到上限，保证磁盘不会被死循环输出打满。</li>
 *   <li><b>取消传播</b> —— 取消时先 destroy 子进程（{@link ProcessHandle#descendants()}），
 *       再 destroy 父进程，防止父进程退出后子进程被 init 接管变成孤儿。</li>
 *   <li><b>超时与取消解耦</b> —— 超时由 {@link Process#waitFor(long, TimeUnit)} 处理，
 *       取消由 {@link ExecutionGate} 的取消标志处理。两条路径都会触发 destroyTree，
 *       调用方按状态字段区分原因。</li>
 *   <li><b>终止验证</b> —— destroyForcibly 之后复查 {@code isAlive()}；仍存活时记 ERROR
 *       并置位 lingering，不再静默放过残留进程。</li>
 * </ol>
 *
 * <p>并发槽位由 {@link ExecutionGate} 的许可承担：{@code acquire} 发放、调用方 close 释放；
 * 本类只负责把进程句柄绑定到许可上（使取消能 kill 到它）。
 */
@Component
@Slf4j
public class ProcessRunner implements CommandExecutor {

    /** destroy 之后等子进程退出的最长时间（超时 / 取消路径）。 */
    private static final long CANCEL_WAIT_SECONDS = 5L;
    /**
     * 进程结束后等它真正退出的上限（正常路径）。
     * 必须等到进程彻底退出，内核才会释放内核重定向的 stdout/stderr 文件句柄，
     * 否则 Windows 上临时目录删不掉、日志尾部也可能没刷盘。
     */
    private static final long EXIT_WAIT_SECONDS = 30L;
    /**
     * 进程退出后等输出文件句柄释放的上限。
     * 重定向的文件句柄会被后代继承，后代比父进程晚死时句柄释放会滞后一小会儿；
     * 等它有界时间，能让 CleanupService 删执行目录 / 测试删临时目录不再偶发失败。
     */
    private static final long HANDLE_RELEASE_WAIT_MS = 3000L;
    /** 句柄释放的轮询间隔。 */
    private static final long HANDLE_RELEASE_POLL_MS = 25L;

    private final ExecutionGate gate;
    private final ScriptBoxProperties props;

    public ProcessRunner(ExecutionGate gate, ScriptBoxProperties props) {
        this.gate = gate;
        this.props = props;
    }

    // ======================================================================
    // CommandExecutor：内部短命令（不占用并发槽位）
    // ======================================================================

    @Override
    public CommandResult exec(CommandSpec spec) throws IOException {
        if (spec.streamOutput()) {
            // 不落盘：让子进程直接继承父进程的 stdout / stderr。
            // 没有输出文件 → 没有句柄继承 → 不存在"后代持有句柄导致清理失败"，
            // 也不需要读回文本（调用方只看退出码与是否超时）。
            SpawnOutcome o = launch(spec.command(), spec.workingDirectory(), spec.environment(),
                    null, null, spec.timeoutSeconds(), spec.label(), 0L, null, true);
            return new CommandResult(o.exitCode(), o.timedOut(), o.cancelled(), o.durationMs(),
                    o.drainFailed() || o.lingering(), null, null, false);
        }

        Path out = spec.stdoutPath();
        Path err = spec.stderrPath();
        List<Path> leased = new ArrayList<>();
        boolean needOutText = out == null;
        boolean needErrText = err == null;
        if (needOutText) {
            out = Files.createTempFile("cmd-out-", ".log");
            leased.add(out);
        }
        if (needErrText) {
            err = Files.createTempFile("cmd-err-", ".log");
            leased.add(err);
        }
        long budget = Math.max(0, spec.maxOutputBytes());
        try {
            SpawnOutcome o = launch(spec.command(), spec.workingDirectory(), spec.environment(),
                    out, err, spec.timeoutSeconds(), spec.label(), budget, null, false);
            long readCap = budget > 0 ? budget : props.getMaxLogBytes();
            String stdoutText = needOutText ? readCapped(out, readCap) : null;
            String stderrText = needErrText ? readCapped(err, readCap) : null;
            boolean truncated = o.capped()
                    || (stdoutText != null && stdoutText.length() >= readCap)
                    || (stderrText != null && stderrText.length() >= readCap);
            return new CommandResult(o.exitCode(), o.timedOut(), o.cancelled(), o.durationMs(),
                    o.drainFailed() || o.lingering(), stdoutText, stderrText, truncated);
        } finally {
            for (Path p : leased) {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            }
        }
    }

    /** 读取文件内容，最多 {@code cap} 字节；失败返回空串（内部命令的输出不应影响主流程）。 */
    private static String readCapped(Path p, long cap) {
        if (p == null) return "";
        try {
            if (!Files.exists(p)) return "";
            long size = Files.size(p);
            long take = cap <= 0 ? size : Math.min(size, cap);
            if (take <= 0) return "";
            int len = (int) Math.min(take, Integer.MAX_VALUE);
            byte[] buf = new byte[len];
            try (InputStream in = Files.newInputStream(p)) {
                int off = 0;
                while (off < len) {
                    int r = in.read(buf, off, len - off);
                    if (r < 0) break;
                    off += r;
                }
                return new String(buf, 0, off, StandardCharsets.UTF_8);
            }
        } catch (IOException ioe) {
            return "";
        }
    }

    // ======================================================================
    // 脚本执行入口（绑定并发槽位许可 + 可取消）
    // ======================================================================

    /**
     * 启动进程，按 {@link ProcessRequest} 配置执行；返回 {@link ProcessResult}。
     *
     * <p>调用方负责：
     * <ul>
     *   <li>确保 {@link ProcessRequest#stdoutPath()} / {@link ProcessRequest#stderrPath()}
     *       的父目录存在（{@code Files.createDirectories}）；</li>
     *   <li>确保命令行安全（不应拼接 {@code bash -c}）；</li>
     *   <li>已经通过 {@link ExecutionGate#acquire} 占住槽位，并在返回后 close 许可。</li>
     * </ul>
     */
    public ProcessResult run(long executionId, ProcessRequest req) throws IOException {
        try {
            SpawnOutcome o = launch(req.command(), req.workingDirectory(), req.environment(),
                    req.stdoutPath(), req.stderrPath(), req.timeoutSeconds(), req.label(),
                    props.getMaxOutputBytes(), executionId, false);
            log.info("execution: 进程结束 executionId={} exitCode={} timeout={} cancelled={} duration={}ms",
                    executionId, o.exitCode(), o.timedOut(), o.cancelled(), o.durationMs());
            return new ProcessResult(o.exitCode(), o.timedOut(), o.cancelled(), o.durationMs(),
                    req.stdoutPath(), req.stderrPath(), o.drainFailed() || o.lingering());
        } finally {
            // 注意：这里不释放并发槽位 —— 槽位由 ScriptExecutor 持有的
            // ExecutionGate.Permit 在整段执行（含 result.json 解析、artifact 扫描、
            // 落库）结束后统一释放。
            gate.releaseProcess(executionId);
        }
    }

    // ======================================================================
    // 内部：进程启动与等待
    // ======================================================================

    /**
     * 启动进程、按需绑定取消许可、等待结束、彻底回收。
     *
     * @param outputBudget 单流字节上限；>0 时在进程结束后把超限的文件截到该长度
     * @param executionId  非 null 时绑定到已有许可（由 {@link ExecutionGate#acquire} 发放）
     * @param inheritOutput true 时不设置任何重定向，子进程直接继承父进程的 stdout/stderr
     *                      （无输出文件、无句柄继承，适合只看退出码的内部命令）
     */
    private SpawnOutcome launch(List<String> command, Path workDir, Map<String, String> env,
                                Path stdoutPath, Path stderrPath, int timeoutSeconds, String label,
                                long outputBudget, Long executionId, boolean inheritOutput)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        // redirectErrorStream=false — stdout 与 stderr 分开处理，避免合并后改变输出顺序，
        // 干扰 result.json / artifact 解析。
        pb.redirectErrorStream(false);
        if (env != null) {
            pb.environment().putAll(env);
        }
        // 内核重定向：逐字节保真、零解码、零内存占用、无管道阻塞风险。
        //
        // 句柄注意：重定向的文件句柄会被子进程及其**后代**继承。后代若在强杀后仍然
        // 存活，它会继续持有该句柄，导致文件/目录删不掉（Windows 上表现为清理失败）。
        // 因此销毁进程树时必须等子孙真正消失 —— 见 ExecutionGate.destroyTree 的
        // waitForDescendants 参数；只看退出码的内部命令则改用 inheritOutput 彻底绕开文件。
        if (!inheritOutput) {
            if (stdoutPath != null) pb.redirectOutput(stdoutPath.toFile());
            if (stderrPath != null) pb.redirectError(stderrPath.toFile());
        }

        Process process = pb.start();
        long startMs = System.currentTimeMillis();
        boolean bound = false;
        boolean lingering = false;
        if (executionId != null) {
            // 两种"查不到许可"要区别对待：
            //   - 许可从未存在过（executionId 为空 / 调用方没走 acquire）：正常执行；
            //   - 许可被取消后又立刻 close 掉了：必须"空转"而不是真的起进程，
            //     否则会留下一个没人能取消的进程。
            bound = gate.bindProcess(executionId, process);
            if (!bound && gate.isKnown(executionId)) {
                log.warn("execution: 许可已被取消并回收，放弃启动 label={} executionId={}",
                        label, executionId);
                destroyTree(process, false);
                awaitQuietly(process, CANCEL_WAIT_SECONDS);
                return new SpawnOutcome(-1, false, true, 0L, true, process.isAlive(), false);
            }
        }

        try {
            // 超时判定不能只看 waitFor 的布尔返回值：它返回 false 既可能是"真的超时"，
            // 也可能是"进程其实已退出、但 onExit 通知尚未送达"（Windows 上文件句柄
            // 尚未释放时很常见）。旧实现把 false 直接当成超时，结果 `bash -c "exit 7"`
            // 的退出码 7 被改写成哨兵值 -1，并误报 timeout=true。
            //
            // 正确做法：waitFor 返回 false 之后再问一次"还活着吗"，只有确实还活着才算超时。
            boolean timedOut = false;
            try {
                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished && process.isAlive()) {
                    timedOut = true;
                    destroyTree(process, true);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                // 中断也按"未完成"处理；cancel 检查会兜底 destroy
            }

            // 如果调用方在 waitFor 之后又 cancel 了，再做一次兜底 destroy。
            boolean cancelled = false;
            if (!timedOut && bound) {
                ExecutionGate.Permit live = gate.get(executionId);
                if (live != null && live.cancelled()) {
                    cancelled = true;
                    destroyTree(process, true);
                }
            }

            // 进程真正结束前必须等干净：
            //   1) 让内核把重定向缓冲刷盘，否则日志丢尾部、exitValue 拿不到；
            //   2) 让内核释放 stdout/stderr 文件句柄，否则 Windows 上目录删不掉。
            // 兜底为一个很宽的上限；仍未退出说明 kill 也杀不掉，如实标记超时。
            if (!awaitQuietly(process, EXIT_WAIT_SECONDS)) {
                timedOut = true;
                lingering = true;
                log.error("execution: 进程在 {}s 内未退出 label={} pid={} alive={}",
                        EXIT_WAIT_SECONDS, label, process.pid(), process.isAlive());
            }

            int exitCode;
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ex) {
                exitCode = -1;
            }
            // 超时或取消的进程没有合法退出码；统一置 -1 让调用方靠 timeout/cancelled 字段判断
            if (timedOut || cancelled) exitCode = -1;

            // 预算兜底：进程已结束，此时把超限输出截到上限。
            boolean capped = truncateToBudget(stdoutPath, outputBudget, label)
                    | truncateToBudget(stderrPath, outputBudget, label);

            // 等内核真正释放重定向文件句柄。
            // 进程退出与句柄释放不是同一时刻：后代进程若稍晚才死，会短暂继续持有句柄，
            // 期间 CleanupService 删执行目录 / 测试删临时目录都会失败。这里做有界轮询，
            // 把"清理偶发失败"变成确定性问题。
            boolean handlesReleased = awaitHandlesReleased(stdoutPath)
                    & awaitHandlesReleased(stderrPath);
            if (!handlesReleased) {
                log.warn("execution: 输出文件句柄未在 {}ms 内释放 label={}（清理时可能失败）",
                        HANDLE_RELEASE_WAIT_MS, label);
            }

            return new SpawnOutcome(exitCode, timedOut, cancelled,
                    System.currentTimeMillis() - startMs, capped | !handlesReleased, lingering, capped);
        } finally {
            if (bound) {
                gate.releaseProcess(executionId);
            }
            if (process.isAlive()) {
                destroyTree(process, true);
                awaitQuietly(process, CANCEL_WAIT_SECONDS);
                if (process.isAlive()) {
                    log.error("execution: 进程残留 label={} executionId={} pid={}，需人工确认",
                            label, executionId, process.pid());
                }
            }
        }
    }

    /**
     * 有界轮询等待输出文件可被清理（= 内核已释放写句柄）。
     *
     * <p>只对文件的<b>父目录</b>做一次"能否创建同名探针文件并删除"的探测，不改动、
     * 不删除真正的日志文件（日志要留给前端查看）。
     *
     * @return true 表示已可清理；文件不存在视为已释放
     */
    private static boolean awaitHandlesReleased(Path path) {
        if (path == null || !Files.exists(path)) return true;
        long deadline = System.currentTimeMillis() + HANDLE_RELEASE_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (canBeDeleted(path)) return true;
            try {
                Thread.sleep(HANDLE_RELEASE_POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 判断文件是否已不再被写句柄占用：尝试以独占方式打开再关闭。
     * 不改动文件内容。
     */
    private static boolean canBeDeleted(Path path) {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                path, java.nio.file.StandardOpenOption.WRITE)) {
            return ch.isOpen();
        } catch (IOException locked) {
            return false;
        }
    }

    /**
     * 把超过预算的输出文件截到 {@code budget} 字节（保留头部）。
     *
     * @return true 表示确实发生了截断
     */
    private static boolean truncateToBudget(Path path, long budget, String label) {
        if (path == null || budget <= 0) return false;
        try {
            if (!Files.exists(path)) return false;
            long size = Files.size(path);
            if (size <= budget) return false;
            try (var ch = Files.newByteChannel(path)) {
                ch.truncate(budget);
            }
            log.warn("execution: 输出超限已截断 label={} path={} {} -> {} bytes",
                    label, path, size, budget);
            return true;
        } catch (IOException ioe) {
            log.warn("execution: 截断输出失败 label={} path={}: {}", label, path, ioe.getMessage());
            return false;
        }
    }

    /**
     * 销毁进程及其子孙进程。实现收敛在 {@link ExecutionGate#destroyTree(Process, boolean)}，
     * 让 cancel API 与 ProcessRunner 共用同一份子孙遍历逻辑，避免两处漂移。
     *
     * @param waitForDescendants 是否在强杀后等子孙真正消失（见 ExecutionGate 的说明）
     */
    private static void destroyTree(Process process, boolean waitForDescendants) {
        ExecutionGate.destroyTree(process, waitForDescendants);
    }

    /** 等待进程退出。返回 true 表示已退出（或等到了结束）。 */
    private static boolean awaitQuietly(Process process, long seconds) {
        try {
            return process.waitFor(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    /**
     * 单次 spawn 的原始结果（未包成对外类型）。
     *
     * @param drainFailed 输出落盘被认为不完整（截断 / 残留进程）
     * @param capped      输出确实被预算截断
     */
    private record SpawnOutcome(int exitCode, boolean timedOut, boolean cancelled,
                                long durationMs, boolean drainFailed, boolean lingering,
                                boolean capped) { }
}
