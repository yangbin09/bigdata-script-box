package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.entity.ExecutionHistory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * V2: registry of currently-running executions. Each entry stores the {@link Process}
 * plus enough metadata to (a) cleanly destroy the process and its descendants on
 * cancel, and (b) record the cancellation outcome afterwards.
 *
 * <p>The map is keyed by executionId. Entries are added at the very moment a
 * {@link Process} is spawned and removed as soon as the executor thread finalises
 * the {@link ExecutionHistory} row. Cancellation uses {@code destroyForcibly} +
 * {@link ProcessHandle#descendants()} to take down subprocesses too.
 *
 * <p>The cancel API never touches a process whose executionId is unknown — that
 * means the execution has already finished and there is nothing to kill.
 */
@Component
public class RunningExecutionRegistry {

    public static class RunningExecution {
        public final long executionId;
        public final long scriptId;
        public final long tenantId;
        public final long startedAtMs;
        public final Process process;
        public final AtomicBoolean cancelled = new AtomicBoolean(false);
        public final AtomicBoolean finished = new AtomicBoolean(false);

        public RunningExecution(long executionId, long scriptId, long tenantId,
                                long startedAtMs, Process process) {
            this.executionId = executionId;
            this.scriptId = scriptId;
            this.tenantId = tenantId;
            this.startedAtMs = startedAtMs;
            this.process = process;
        }
    }

    private final Map<Long, RunningExecution> live = new ConcurrentHashMap<>();

    public void register(long executionId, long scriptId, long tenantId,
                         long startedAtMs, Process process) {
        live.put(executionId, new RunningExecution(executionId, scriptId, tenantId,
                startedAtMs, process));
    }

    /** Mark this execution as finished so future cancels are no-ops. */
    public void unregister(long executionId) {
        RunningExecution re = live.remove(executionId);
        if (re != null) re.finished.set(true);
    }

    public RunningExecution get(long executionId) {
        return live.get(executionId);
    }

    public int activeCount() {
        return live.size();
    }

    /** Snapshot of all currently-running executions. Useful for tests and
     *  the rare admin view that wants to enumerate live processes. */
    public java.util.Collection<RunningExecution> activeExecutions() {
        return java.util.Collections.unmodifiableCollection(live.values());
    }

    /**
     * Cancel the running execution: sets the cancelled flag, calls destroyForcibly
     * on the immediate process, and recursively destroys descendants so we don't
     * leave orphans. Safe to call when the process has already exited — no-op.
     *
     * @return true if a live execution was found and signalled; false otherwise
     *         (already finished, or never existed).
     */
    public boolean cancel(long executionId) {
        RunningExecution re = live.get(executionId);
        if (re == null) return false;
        if (re.finished.get()) return false;
        if (re.cancelled.get()) return false; // already signalled; second call is a no-op
        re.cancelled.set(true);
        try {
            // Destroy children first so they don't notice the parent dying and
            // attempt to fork-spawn more work.
            ProcessHandle child = re.process.toHandle();
            for (ProcessHandle d : child.descendants().toList()) {
                try { d.destroyForcibly(); } catch (Exception ignored) {}
            }
            re.process.destroyForcibly();
        } catch (Exception ignored) {}
        return true;
    }
}
