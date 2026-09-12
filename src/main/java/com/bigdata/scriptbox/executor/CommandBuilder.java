package com.bigdata.scriptbox.executor;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.service.GlobalVariableService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令行与环境变量构造器（#1 / 拆分 ScriptExecutor）。
 *
 * <p>把原 {@code ScriptExecutor} 里 4 处逐字几乎相同的 {@code new ArrayList<>()}
 * 命令拼装（{@code buildDirectCommand} / {@code buildKinitWrappedCommand} /
 * {@code buildArgsFromMap}）合并到一个统一的 {@link #build(ExecutionContext)} 入口，
 * 让命令生成的逻辑只在一处表达。{@code preview()} 仍用 {@link #buildForPreview} 拿到
 * 同样的语义（仅 wrapper 路径占位不同）。
 *
 * <p>所有命令行参数通过 {@code List<String>} 形式传入 {@code ProcessBuilder}，
 * 禁止 shell 字符串拼接，从根上消除用户输入被解释为 shell 元字符的风险。
 */
@Component
@RequiredArgsConstructor
public class CommandBuilder {

    private final ScriptBoxProperties props;
    private final GlobalVariableService globalVariableService;

    /**
     * 为真实执行构造命令行。第一项永远是配置的 shell（{@code scriptbox.shell-executable}），
     * 后续项是脚本路径 / wrapper 路径 + 参数。
     */
    public List<String> build(ExecutionContext ctx) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getShellExecutable());
        Path target = ctx.kinitWrapped() ? ctx.wrapperPath() : ctx.scriptPath();
        cmd.add(target.toString());
        cmd.addAll(buildArgsFromMap(ctx.params()));
        return List.copyOf(cmd);
    }

    /**
     * 为 preview 构造命令行：kinit wrapper 还没生成，用占位符
     * {@code "<executions-dir>/<exec-id>/kinit_wrap_*.sh"} 提示用户。
     */
    public List<String> buildForPreview(ExecutionContext ctx) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getShellExecutable());
        if (ctx.kinitWrapped()) {
            cmd.add("<executions-dir>/<exec-id>/kinit_wrap_*.sh");
        } else {
            cmd.add(ctx.scriptPath().toString());
        }
        cmd.addAll(buildArgsFromMap(ctx.params()));
        return List.copyOf(cmd);
    }

    /**
     * 构造子进程环境变量：全局变量 + 执行上下文 + locale 兜底。
     */
    public Map<String, String> buildEnv(ExecutionContext ctx) {
        Map<String, String> env = new LinkedHashMap<>(globalVariableService.envForExecution());
        env.put("EXECUTION_ID", String.valueOf(ctx.executionId()));
        env.put("EXECUTION_DIR", ctx.executionDir().toAbsolutePath().toString());
        env.put("ARTIFACT_DIR", ctx.artifactDir().toAbsolutePath().toString());
        // 强制 UTF-8 locale：否则子进程按宿主机 locale 编码输出（如中文 Windows GBK），
        // 落盘的 stdout/stderr 不是合法 UTF-8，读取时抛 MalformedInputException。
        // 只补默认值，允许脚本作者在全局变量里显式覆盖。
        env.putIfAbsent("LANG", "C.UTF-8");
        env.putIfAbsent("LC_ALL", "C.UTF-8");
        return env;
    }

    /** 把 {@code params} map 摊平成 {@code --key value} 列表。 */
    public List<String> buildArgsFromMap(Map<String, String> params) {
        List<String> args = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            args.add("--" + e.getKey());
            args.add(e.getValue());
        }
        return List.copyOf(args);
    }
}