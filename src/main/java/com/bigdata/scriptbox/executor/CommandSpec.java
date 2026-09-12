package com.bigdata.scriptbox.executor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 一次子进程执行的完整描述（与 {@link CommandExecutor} 配套）。
 *
 * <p>本 record 是所有 {@code ProcessBuilder} 调用的唯一入参形态。项目里曾经有
 * 三处互相独立的进程启动实现（{@link ProcessRunner}、{@code SyntaxCheckService}、
 * {@code TenantController}），只有第一处带超时与子进程回收。收敛到本 record 之后，
 * 超时 / drain / destroyTree / 资源回收只有一份实现。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@link #command} —— 命令行（{@code List<String>} 形式，禁止拼接字符串）；</li>
 *   <li>{@link #workingDirectory} —— 进程工作目录，null 表示继承 JVM 工作目录；</li>
 *   <li>{@link #environment} —— 追加/覆盖的环境变量，null 或空表示只继承 JVM 环境；</li>
 *   <li>{@link #stdoutPath} / {@link #stderrPath} —— 输出落盘路径；null 表示丢弃该流
 *       （内部短命令只关心退出码与合并输出时用得上）；</li>
 *   <li>{@link #timeoutSeconds} —— 硬超时，必须为正数；超时后 destroy 整棵进程树；</li>
 *   <li>{@link #label} —— 日志与线程名前缀，用于定位是哪个调用点；</li>
 *   <li>{@link #maxOutputBytes} —— 单流写入上限，0 表示不限；</li>
 *   <li>{@link #streamOutput} —— true 时把子进程输出合并到父进程的 stdout/stderr，
 *       <b>完全不落盘</b>。适合 {@code bash -n} 语法检查这类只关心退出码的内部命令：
 *       没有输出文件就没有文件句柄继承问题，也就不存在"后代持有句柄导致清理失败"。
 *       false 时输出写入 {@link #stdoutPath()} / {@link #stderrPath()}。</li>
 * </ul>
 *
 * <p>内部短命令（{@code bash -n} 语法检查、{@code kinit} 连通性测试）通过
 * {@link CommandExecutor#exec} 执行，<b>不占用</b>全局并发槽位；只有用户脚本执行
 * 走 {@code ProcessRunner#run(executionId, ProcessRequest)} 并绑定
 * {@link com.bigdata.scriptbox.service.ExecutionGate} 的许可。
 */
public record CommandSpec(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Path stdoutPath,
        Path stderrPath,
        int timeoutSeconds,
        String label,
        long maxOutputBytes,
        boolean streamOutput
) {

    public CommandSpec {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command is required");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        command = List.copyOf(command);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        label = (label == null || label.isBlank()) ? "cmd" : label;
        maxOutputBytes = Math.max(0, maxOutputBytes);
    }

    /**
     * 便捷构造：不限制输出、不落盘（输出直接透传到父进程），适合
     * {@code bash -n} / {@code kinit} 这类只看退出码、且输出通常只有一两行诊断的内部命令。
     */
    public static CommandSpec of(List<String> command, int timeoutSeconds, String label) {
        return new CommandSpec(command, null, Map.of(), null, null,
                timeoutSeconds, label, 0L, true);
    }
}
