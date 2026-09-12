package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 执行准入闸门：并发槽位 + 同脚本去重 + 取消状态的<b>唯一</b>权威。
 *
 * <p><b>为什么需要它</b>：历史上并发控制是 check-then-act 竞态，分散在三处 ——
 * {@code ScriptExecutor.prepareContext} 遍历 registry 判断同脚本是否在跑、
 * {@code ScriptExecutor.startProcess} 再读一次 {@code activeCount()} 对比
 * {@code maxConcurrent}、{@code ProcessRunner} 最后才真正 register。检查与登记之间
 * 存在任意长的窗口（参数校验、preset 查询、建目录、写 wrapper），因此 N 个并发请求
 * 可以同时通过检查，把 {@code maxConcurrent} 静默击穿。
 *
 * <p>本类的 {@link #acquire} 在<b>单个 {@code synchronized} 临界区</b>内同时完成
 * "同脚本冲突检查 + 槽位余量检查 + 占位登记"，窗口为零。
 *
 * <p><b>生命周期</b>：{@link Permit} 一旦获得，槽位就从准入一直持有到
 * {@link Permit#close()}（由调用方的 try-with-resources 保证）。持有期内允许
 * {@link Permit#bindProcess} 绑定真实进程句柄，于是 {@link #cancel} 能在任意时刻
 * 生效。槽位释放与业务收尾严格对齐，不会出现"进程还在写库但槽位已放行"。
 *
 * <p>注意：内部短命令（{@code bash -n} 语法检查、{@code kinit} 连通性测试）
 * 不经过本闸门，因此不会挤占用户脚本的执行名额。
 */
@Component
@Slf4j
public class ExecutionGate {

    /** 取消状态机：占位 → 运行 → 已请求取消 → 已终结。 */
    public enum State { RESERVED, RUNNING, CANCELLING, FINISHED }

    /**
     * 强杀进程树后等待子孙消失的上限。
     *
     * <p>必须等：孙进程（如 Windows 上 Git Bash 的 {@code sleep}）若继续存活，会一直
     * 持有内核重定向的 stdout/stderr 文件句柄（临时目录删不掉），并可能变成孤儿进程。
     * 上限保持很短，避免拖慢取消响应。
     */
    private static final long DESCENDANT_WAIT_MS = 1500L;

    /**
     * 一张已发放的准入许可。必须 close（建议 try-with-resources）。
     *
     * <p>字段与访问器并存：{@code executionId()} 等访问器供新代码使用；
     * {@link #cancelled()} 暴露取消状态，供 cancel 语义与可观测端点使用。
     */
    public final class Permit implements AutoCloseable {

        private final long executionId;
        private final long scriptId;
        private final long tenantId;
        private final long startedAtMs;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile Process process;
        private volatile State state = State.RESERVED;

        private Permit(long executionId, long scriptId, long tenantId, long startedAtMs) {
            this.executionId = executionId;
            this.scriptId = scriptId;
            this.tenantId = tenantId;
            this.startedAtMs = startedAtMs;
        }

        public long executionId() { return executionId; }
        public long scriptId() { return scriptId; }
        public long tenantId() { return tenantId; }
        public long startedAtMs() { return startedAtMs; }
        public boolean cancelled() { return cancelled.get(); }
        public boolean finished() { return finished.get(); }
        public State state() { return state; }

        /** 绑定真实进程句柄；绑定后 {@link ExecutionGate#cancel} 才能 kill 到它。 */
        public void bindProcess(Process p) {
            this.process = p;
            if (cancelled.get()) {
                // 绑定前的取消请求不能丢：补一次 destroy
                destroyTree(p, false);
            } else if (state == State.RESERVED) {
                state = State.RUNNING;
            }
        }

        @Override
        public void close() {
            permits.remove(executionId, this);
            finished.set(true);
            state = State.FINISHED;
            occupied.decrementAndGet();
        }
    }

    private final ScriptBoxProperties props;
    /** executionId → permit。ConcurrentHashMap 仅用于无锁读；写侧走 synchronized。 */
    private final Map<Long, Permit> permits = new ConcurrentHashMap<>();
    /**
     * 曾被取消、随后已 close 的 executionId。
     *
     * <p>{@code ProcessRunner} 用它区分"许可从来不存在"（正常启动）与
     * "许可被取消后回收"（必须空转，不能留下无人能取消的进程）。
     */
    private final java.util.Set<Long> revoked = ConcurrentHashMap.newKeySet();
    /** 当前被占用的槽位数（含尚未绑定进程的 RESERVED 许可）。 */
    private final AtomicInteger occupied = new AtomicInteger(0);

    public ExecutionGate(ScriptBoxProperties props) {
        this.props = props;
    }

    /**
     * 原子地申请一个执行槽位。
     *
     * <p>在同一个临界区内完成三件事，因此不存在竞态窗口：
     * <ol>
     *   <li>{@code allowConcurrent=false} 时，拒绝同脚本的第二次执行；</li>
     *   <li>占用槽位达到 {@code scriptbox.max-concurrent} 时，拒绝新执行；</li>
     *   <li>成功时立刻登记占位（含 executionId），使后续检查能看到它。</li>
     * </ol>
     *
     * @param executionId 本次执行的 ID（调用方在准入前生成）
     * @param scriptId    脚本 ID
     * @param tenantId    租户 ID
     * @param scriptName  脚本名（仅用于错误消息）
     * @param allowConcurrent 该脚本是否允许并发
     * @return 许可；被拒绝时返回携带原因的 {@link Rejection}
     */
    public synchronized Admission acquire(long executionId, long scriptId, long tenantId,
                                         String scriptName, boolean allowConcurrent) {
        if (!allowConcurrent) {
            for (Permit p : permits.values()) {
                if (p.scriptId == scriptId && !p.finished.get()) {
                    return Admission.rejected(new Rejection(
                            Rejection.Kind.ALREADY_RUNNING,
                            "脚本 '" + scriptName + "' 已有运行中的执行（already running，executionId="
                                    + p.executionId + "），请等待完成或在脚本上启用 allowConcurrent=true",
                            p.executionId, 0));
                }
            }
        }

        int cap = props.getMaxConcurrent();
        int current = occupied.get();
        if (current >= cap) {
            return Admission.rejected(new Rejection(
                    Rejection.Kind.SLOT_LIMIT,
                    "执行槽位已满（slot limit reached, active=" + current
                            + ", max-concurrent=" + cap
                            + "），请等待正在运行的脚本结束或调大 scriptbox.max-concurrent",
                    -1L, cap));
        }

        Permit permit = new Permit(executionId, scriptId, tenantId, System.currentTimeMillis());
        permits.put(executionId, permit);
        revoked.remove(executionId);
        occupied.incrementAndGet();
        log.info("execution: 准入通过 executionId={} scriptId={} tenantId={} occupied={}/{}",
                executionId, scriptId, tenantId, occupied.get(), cap);
        return Admission.granted(permit);
    }

    /** 解绑进程句柄（进程已结束），但<b>不释放槽位</b> —— 槽位由许可 close 时释放。 */
    public void releaseProcess(long executionId) {
        Permit p = permits.get(executionId);
        if (p != null) p.process = null;
    }

    /**
     * 该 executionId 是否曾被本闸门取消过。
     *
     * <p>用于区分两种"查不到许可"：
     * <ul>
     *   <li>从未登记 → 调用方没走 {@link #acquire}（例如单测 / 轻量调用方），
     *       {@code ProcessRunner} 应正常启动进程；</li>
     *   <li>登记过且被取消 → 进程绝不能真的起来（没人能再取消它），必须空转。</li>
     * </ul>
     */
    public boolean isKnown(long executionId) {
        return revoked.contains(executionId);
    }

    /**
     * 取消一个运行中的执行：立刻销毁进程及其子孙进程，并置位取消标志供执行线程读取。
     *
     * <p>幂等：进程已退出、执行已终结、或已发送过取消信号，都返回 false。
     * 状态迁移在临界区内完成，因此不会出现"读到旧引用后对象已被移除"的窗口。
     *
     * @return true 表示本次调用真正发送了取消信号
     */
    public synchronized boolean cancel(long executionId) {
        Permit p = permits.get(executionId);
        if (p == null) return false;
        if (p.finished.get() || p.cancelled.get()) return false;
        p.cancelled.set(true);
        p.state = State.CANCELLING;
        revoked.add(executionId);
        destroyTree(p.process, true);
        log.info("execution: 取消信号已发送 executionId={}", executionId);
        return true;
    }

    /**
     * 把真实进程句柄绑定到已发放的许可上。
     *
     * <p>由 {@code ProcessRunner} 在 {@code pb.start()} 之后调用；绑定之后
     * {@link #cancel} 才能 kill 到该进程。若许可在绑定之前就已被取消，
     * 这里会立即销毁该进程（不丢失取消请求）。
     *
     * @return 绑定是否成功（许可不存在 / 已终结时返回 false）
     */
    public boolean bindProcess(long executionId, Process process) {
        Permit p = permits.get(executionId);
        if (p == null || p.finished.get()) return false;
        p.bindProcess(process);
        return true;
    }

    /** 按 executionId 取许可；不存在返回 null。 */
    public Permit get(long executionId) {
        Permit p = permits.get(executionId);
        return (p == null || p.finished.get()) ? null : p;
    }

    /** 当前被占用的槽位数（含已准入但尚未启动进程的执行）。 */
    public int occupied() {
        return occupied.get();
    }

    /** 当前登记在案的许可数（RESERVED + RUNNING + CANCELLING，不含 FINISHED）。 */
    public int activeCount() {
        int n = 0;
        for (Permit p : permits.values()) {
            if (!p.finished.get()) n++;
        }
        return n;
    }

    /** 剩余可用槽位数。 */
    public int availableSlots() {
        return Math.max(0, props.getMaxConcurrent() - occupied.get());
    }

    /** 全部在途许可的快照（不可修改视图）。 */
    public Collection<Permit> activePermits() {
        return Collections.unmodifiableCollection(permits.values());
    }

    /** 是否正在运行（供测试 / 内部使用）。 */
    public boolean isRunning(long executionId) {
        Permit p = permits.get(executionId);
        return p != null && !p.finished.get();
    }

    /**
     * 销毁进程及其子孙进程。cancel 接口与 ProcessRunner 共用本方法，
     * 避免两处各自实现子孙遍历的逻辑漂移。
     *
     * @param waitForDescendants true 时在强杀后短轮询等待子孙进程真正消失。
     *       取消失效的关键路径上必须为 true：孙进程若还活着，它会继续持有内核重定向
     *       的 stdout/stderr 文件句柄（Windows 上表现为"目录删不掉"），而且被 init
     *       接管后会变成真正的孤儿进程。等待上限很短（{@link #DESCENDANT_WAIT_MS}），
     *       不会拖慢取消响应。
     */
    public static void destroyTree(Process process, boolean waitForDescendants) {
        if (process == null) return;
        java.util.List<ProcessHandle> descendants = java.util.List.of();
        try {
            ProcessHandle handle = process.toHandle();
            descendants = handle.descendants().toList();
            for (ProcessHandle d : descendants) {
                try { d.destroyForcibly(); } catch (Exception ignored) { }
            }
            process.destroyForcibly();
        } catch (Exception ignored) {
            // best-effort
        }
        if (waitForDescendants && !descendants.isEmpty()) {
            awaitDescendantsGone(descendants);
        }
    }

    /** 短轮询等子孙进程消失；超时即返回，绝不长期阻塞取消路径。 */
    private static void awaitDescendantsGone(java.util.List<ProcessHandle> descendants) {
        long deadline = System.currentTimeMillis() + DESCENDANT_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            boolean anyAlive = false;
            for (ProcessHandle d : descendants) {
                if (d.isAlive()) { anyAlive = true; break; }
            }
            if (!anyAlive) return;
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("execution: 有子孙进程在 {}ms 内未退出，可能成为孤儿", DESCENDANT_WAIT_MS);
    }

    /** {@link #acquire} 的结果：要么是许可，要么是拒绝原因。 */
    public record Admission(Permit permit, Rejection rejection) {

        static Admission granted(Permit p) { return new Admission(p, null); }
        static Admission rejected(Rejection r) { return new Admission(null, r); }

        public boolean isGranted() { return permit != null; }

        /** 被拒绝时抛出携带原始消息的异常，保持与旧实现一致的异常类型与文案。 */
        public Permit orThrow() {
            if (permit != null) return permit;
            throw new IllegalStateException(rejection.message());
        }
    }

    /** 准入被拒绝的原因。 */
    public record Rejection(Kind kind, String message, long conflictingExecutionId, int cap) {
        public enum Kind { ALREADY_RUNNING, SLOT_LIMIT }
    }
}
