package com.bigdata.scriptbox.executor;

/**
 * 一次子进程执行的结果（与 {@link CommandExecutor} 配套）。
 *
 * <p>相比 {@link ProcessResult}（脚本执行专用，带 artifact / 日志路径语义），
 * 本 record 是面向"任意子进程"的通用结果，额外携带被截断的 stdout / stderr 文本，
 * 供语法校验、连通性测试这类需要读取输出的调用方直接使用。
 *
 * @param exitCode     退出码；超时 / 取消时为 -1
 * @param timeout      是否因超时被杀
 * @param cancelled    是否被显式取消
 * @param durationMs   墙钟耗时
 * @param drainFailed  输出写入是否失败（输出可能不完整）
 * @param stdout       捕获的 stdout 文本（未捕获时为 null）
 * @param stderr       捕获的 stderr 文本（未捕获时为 null）
 * @param truncated    输出是否因超出上限被截断
 */
public record CommandResult(
        int exitCode,
        boolean timeout,
        boolean cancelled,
        long durationMs,
        boolean drainFailed,
        String stdout,
        String stderr,
        boolean truncated
) {

    /** 是否正常退出（退出码 0，且未超时、未取消）。 */
    public boolean ok() {
        return exitCode == 0 && !timeout && !cancelled;
    }

    /** 合并 stdout 与 stderr，按 stdout 在前、stderr 在后拼接；两者都为空时返回空串。 */
    public String combinedOutput() {
        String out = stdout == null ? "" : stdout;
        String err = stderr == null ? "" : stderr;
        if (err.isEmpty()) return out;
        if (out.isEmpty()) return err;
        return out + (out.endsWith("\n") ? "" : "\n") + err;
    }
}
