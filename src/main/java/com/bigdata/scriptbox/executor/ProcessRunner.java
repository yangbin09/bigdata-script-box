package com.bigdata.scriptbox.executor;

import com.bigdata.scriptbox.service.RunningExecutionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程执行器。
 *
 * <p>封装 ProcessBuilder 启动 + stdout/stderr 异步消费 + 超时 + 取消 + 退出码
 * 这些"通用样板"，让 ScriptExecutor 只关心业务参数。
 *
 * <p>关键设计点：
 * <ol>
 *   <li><b>stdout 与 stderr 同时异步消费</b> — 必须并行 drain，否则管道缓冲区
 *       满后进程会阻塞。这是 {@link ProcessBuilder} 一个非常容易踩的坑。</li>
 *   <li><b>取消传播</b> — 取消时先 destroy 子进程（{@link ProcessHandle#descendants()}），
 *       再 destroy 父进程，防止父进程退出后子进程被 init 接管变成孤儿。</li>
 *   <li><b>超时与取消解耦</b> — 超时由 {@link Process#waitFor(long, TimeUnit)} 处理，
 *       取消由 {@link RunningExecutionRegistry} 的 cancel 标志处理。两条路径都会
 *       触发 destroyForcibly，调用方按状态字段区分原因。</li>
 *   <li><b>线程守护</b> — drain 线程设为 daemon=true，JVM 退出时不会阻塞。</li>
 * </ol>
 *
 * <p>为何不直接用 try-with-resources 关闭 Process：Process 持有文件描述符，
 * 但 stdout / stderr 流的关闭语义不稳定。手动管理 + finally 等待 drain 线程
 * 是更可靠的做法。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ProcessRunner {

    /** drain 线程最多再等 2 秒，避免 cancel 后 drain 永远 hang。 */
    private static final long DRAIN_JOIN_MS = 2000L;
    /** cancel 后等子进程退出的最长时间。 */
    private static final long CANCEL_WAIT_SECONDS = 5L;

    private final RunningExecutionRegistry registry;

    /**
     * 启动进程，按 {@link ProcessRequest} 配置执行；返回 {@link ProcessResult}。
     *
     * <p>调用方负责：
     * <ul>
     *   <li>确保 {@link ProcessRequest#stdoutPath()} / {@link ProcessRequest#stderrPath()}
     *       的父目录存在（{@code Files.createDirectories}）。</li>
     *   <li>确保环境变量、命令行安全（不应拼接 bash -c）。</li>
     *   <li>调用 {@link RunningExecutionRegistry#register} 后再调用本方法，
     *       便于 cancel 能在中途 fire。</li>
     * </ul>
     *
     * <p>异常安全：本方法无论成功还是异常路径都会从 {@link RunningExecutionRegistry}
     * 注销当前 executionId；若进程仍在运行，finally 块内会尝试 destroyForcibly，
     * 避免 FD / 句柄泄漏。
     */
    public ProcessResult run(long executionId, ProcessRequest req) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(req.command());
        if (req.workingDirectory() != null) {
            pb.directory(req.workingDirectory().toFile());
        }
        // redirectErrorStream=false — stdout 与 stderr 必须分开消费，否则合并后会
        // 改变输出顺序，对 result.json / artifact 解析造成干扰。
        pb.redirectErrorStream(false);
        if (req.environment() != null) {
            pb.environment().putAll(req.environment());
        }

        long startMs = System.currentTimeMillis();
        Process process = pb.start();
        // 注册到 RunningExecutionRegistry 之前 cancel 拿不到 pid；注册之后任何调用方
        // 都能 cancel(executionId)。registry 不负责启动，只负责跟踪。
        registry.register(executionId, req.scriptId(), req.tenantId(), startMs, process);

        // drainFailed 标志：drain 线程任一发生 IO 异常则置 true；调用方据此在
        // history / 日志里提示用户"输出可能不完整"，而不是静默丢消息。
        AtomicBoolean drainFailed = new AtomicBoolean(false);

        // 整个执行生命周期放在 try/finally，保证 registry.unregister 必然执行。
        // 即便 waitFor / drain 抛异常，也不会留下 stale registry 条目阻塞后续 slot 检查。
        try (java.io.InputStream stdout = process.getInputStream();
             java.io.InputStream stderr = process.getErrorStream()) {

            // 启动两个 daemon 线程并行 drain stdout / stderr。
            Thread drainOut = drainAsync(stdout, req.stdoutPath(), "stdout-" + req.label(), drainFailed);
            Thread drainErr = drainAsync(stderr, req.stderrPath(), "stderr-" + req.label(), drainFailed);

            boolean timedOut = false;
            try {
                boolean finished = process.waitFor(req.timeoutSeconds(), TimeUnit.SECONDS);
                if (!finished) {
                    timedOut = true;
                    // 单一职责：超时 destroy 走 ProcessRunner；cancel destroy 也走这里。
                    destroyTree(process);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                // 中断也按"未完成"处理；cancel 检查会兜底 destroy
            }

            // 如果调用方在 process.waitFor 之后又 cancel 了，再做一次兜底 destroy。
            RunningExecutionRegistry.RunningExecution live = registry.get(executionId);
            boolean cancelled = false;
            if (live != null && live.cancelled.get()) {
                cancelled = true;
                destroyTree(process);
                try { process.waitFor(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }

            // 等 drain 线程把残留的 buffer 写完。
            joinQuietly(drainOut);
            joinQuietly(drainErr);

            int exitCode;
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ex) {
                exitCode = -1;
            }
            // 超时或取消的进程没有合法退出码；统一置 -1 让调用方靠 timeout/cancelled 字段判断
            if (timedOut || cancelled) exitCode = -1;

            long duration = System.currentTimeMillis() - startMs;
            return new ProcessResult(exitCode, timedOut, cancelled, duration,
                    req.stdoutPath(), req.stderrPath(), drainFailed.get());
        } finally {
            // 关键：无论上面是否抛异常，registry 都必须注销。
            // 同时确保进程被销毁（极端情况下：抛异常发生在 waitFor 之前，进程仍在跑）。
            registry.unregister(executionId);
            try {
                if (process.isAlive()) {
                    destroyTree(process);
                }
            } catch (Exception ignored) {
                // best-effort，shutdown 阶段不再抛
            }
        }
    }

    /**
     * 销毁进程及其子孙进程（ProcessHandle.descendants），并等待 5 秒。
     * 单一职责：所有 destroyForcibly 入口集中到这里，避免 cancel API 与 ProcessRunner 各自 destroy 造成 race。
     */
    private static void destroyTree(Process process) {
        try {
            ProcessHandle handle = process.toHandle();
            for (ProcessHandle d : handle.descendants().toList()) {
                try { d.destroyForcibly(); } catch (Exception ignored) {}
            }
            process.destroyForcibly();
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /**
     * 异步 drain 进程输出流到目标文件。线程退出原因：流 EOF、IO 异常或线程被中断。
     * 异常仅记录 WARN，不向上抛（stdout/stderr 写入失败不应影响主流程）；
     * 但会置位 {@code drainFailed} 让主流程在 history 里给运维一个提示。
     *
     * @param in 进程输出流
     * @param target 目标文件
     * @param label 日志标签，便于排查是哪个 drain
     * @param drainFailed 任一 drain 失败即置 true 的共享标志
     * @return 已启动的线程
     */
    private Thread drainAsync(java.io.InputStream in, Path target, String label, AtomicBoolean drainFailed) {
        Thread t = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                 var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                }
            } catch (IOException e) {
                // drain 失败：可能是 stdout 已经被强制关闭（cancel / destroy），
                // 也可能是目标文件路径不可写 / 权限问题。
                // 不影响主流程 exitCode 解读，但需要让调用方知道。
                drainFailed.set(true);
                log.warn("drain {} 失败: {}", label, e.getMessage());
            }
        }, "exec-drain-" + label);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 带超时的 join。中断不抛，仅恢复线程中断标志。
     *
     * @param t 线程
     */
    private static void joinQuietly(Thread t) {
        try {
            t.join(DRAIN_JOIN_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}