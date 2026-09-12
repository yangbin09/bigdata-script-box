package com.bigdata.scriptbox.executor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 进程启动请求。
 *
 * <p>封装一次 {@link ProcessBuilder} 启动所需的全部参数，与 {@link ProcessRunner}
 * 配套使用，让 ScriptExecutor 不必直接关心 Process API。
 *
 * <p>关键字段：
 * <ul>
 *   <li>{@link #command} — 命令行（{@code List<String>} 形式，禁止拼接字符串，
 *       避免 shell 注入）；</li>
 *   <li>{@link #workingDirectory} — 进程工作目录；</li>
 *   <li>{@link #environment} — 环境变量，会覆盖 ProcessBuilder 默认值；</li>
 *   <li>{@link #stdoutPath} / {@link #stderrPath} — 输出落盘路径（必填，
 *       由调用方控制路径必须在受控目录下）；</li>
 *   <li>{@link #timeoutSeconds} — 等待超时（秒），超过则 {@code destroyForcibly}；</li>
 *   <li>{@link #label} — 日志与 drain 线程名前缀。</li>
 * </ul>
 *
 * <p>注意：并发槽位与取消跟踪由 {@link com.bigdata.scriptbox.service.ExecutionGate}
 * 的许可（Permit）承担，本请求不再携带 scriptId / tenantId —— 那些信息在准入时
 * 就已经记录，避免同一份元数据在两处各存一遍、并保持一致性的负担。
 */
public record ProcessRequest(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Path stdoutPath,
        Path stderrPath,
        int timeoutSeconds,
        String label
) {
    public ProcessRequest {
        if (command == null || command.isEmpty())
            throw new IllegalArgumentException("command is required");
        if (stdoutPath == null || stderrPath == null)
            throw new IllegalArgumentException("stdout/stderr paths are required");
        if (timeoutSeconds <= 0)
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        if (label == null || label.isBlank())
            throw new IllegalArgumentException("label is required");
        // 防御性拷贝：构造完成后再修改外部集合无法影响本请求
        command = List.copyOf(command);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
