package com.bigdata.scriptbox.executor;

/**
 * 进程执行结果。
 *
 * <p>封装 {@link ProcessRunner#run} 的输出，便于 ScriptExecutor 后续
 * 写历史 / 写日志。字段含义：
 * <ul>
 *   <li>{@code exitCode} — 进程退出码；-1 表示未正常退出（被强杀 / 仍在跑）；</li>
 *   <li>{@code timeout} — 是否因超时被强杀；</li>
 *   <li>{@code cancelled} — 是否因外部 cancel 被强杀；</li>
 *   <li>{@code durationMs} — 从进程启动到结束的耗时；</li>
 *   <li>{@code stdoutPath} / {@code stderrPath} — 输出落盘路径（运行时被引用，
 *       防止外部后续清理误删）。</li>
 * </ul>
 *
 * <p>为何 {@code exitCode == -1}：Java {@code Process.exitValue()} 在进程
 * 未结束时抛 {@link IllegalThreadStateException}。用 -1 作为哨兵值让调用方
 * 不必处理两种情况。
 */
public record ProcessResult(
        int exitCode,
        boolean timeout,
        boolean cancelled,
        long durationMs,
        java.nio.file.Path stdoutPath,
        java.nio.file.Path stderrPath
) {
    /** @return 是否成功完成（未超时未取消且退出码为 0） */
    public boolean ok() {
        return !timeout && !cancelled && exitCode == 0;
    }
}