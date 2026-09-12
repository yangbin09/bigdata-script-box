package com.bigdata.scriptbox.service.precheck;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 命令存在检查：检查命令是否在 PATH 中，或以绝对路径给出时是否可执行。
 *
 * <p>对应 JSON key：{@code "commands"}，值为字符串数组。
 *
 * <p>特例：当被检查的命令名恰好是配置的 shell（{@code scriptbox.shell-executable}）的
 * 可执行名时，先按该配置解析（容忍 Windows 的 {@code .exe} 后缀）。否则在"shell 不在
 * PATH 上"的部署里（Windows 上用 Git Bash、或把 bash 装在非标准路径），
 * {@code commands:["bash"]} 会误报失败 —— 而脚本其实能跑，precheck 与真实执行能力不一致。
 */
@Component
public class CommandExistsPrecheckStrategy extends AbstractJsonListPrecheckStrategy {

    /** POSIX 下 PATH 的分隔符；Windows 上 java.io.File.pathSeparator 已是 {@code ;}。 */
    private static final String FALLBACK_PATH = "/usr/local/bin:/usr/bin:/bin";

    private final ScriptBoxProperties props;

    public CommandExistsPrecheckStrategy(ObjectMapper mapper, ScriptBoxProperties props) {
        super(mapper);
        this.props = props;
    }

    @Override
    public String configKey() {
        return "commands";
    }

    @Override
    public CheckOutcome check(String cmd, Script script, Tenant tenant) {
        if (cmd == null || cmd.isBlank())
            return CheckOutcome.fail(cmd, "empty command name");

        Path configured = configuredShellFor(cmd);
        if (configured != null) {
            if (Files.exists(configured) && Files.isExecutable(configured))
                return CheckOutcome.pass(cmd, "configured shell: " + configured);
            return CheckOutcome.fail(cmd, "configured shell missing or not executable: " + configured);
        }

        Path p = Paths.get(cmd);
        if (p.isAbsolute()) {
            if (Files.exists(p) && Files.isExecutable(p))
                return CheckOutcome.pass(cmd, "absolute path exists and executable");
            return CheckOutcome.fail(cmd, "not executable");
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) pathEnv = FALLBACK_PATH;
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path candidate = Paths.get(dir, cmd);
            if (Files.exists(candidate) && Files.isExecutable(candidate))
                return CheckOutcome.pass(cmd, "found in " + dir);
        }
        return CheckOutcome.fail(cmd, "not found in PATH");
    }

    /**
     * 若 {@code cmd} 是配置 shell 的可执行名，返回其配置路径；否则返回 {@code null}
     * 表示按常规 PATH 查找。
     */
    private Path configuredShellFor(String cmd) {
        String configured = props.getShellExecutable();
        if (configured == null || configured.isBlank()) return null;
        Path shellPath = Paths.get(configured);
        Path fileName = shellPath.getFileName();
        if (fileName == null) return null;
        String actual = fileName.toString();
        String expected = cmd.trim();
        boolean matches = actual.equalsIgnoreCase(expected)
                || stripExeSuffix(actual).equalsIgnoreCase(stripExeSuffix(expected));
        if (!matches) return null;
        return shellPath.toAbsolutePath().normalize();
    }

    private static String stripExeSuffix(String name) {
        return name.toLowerCase().endsWith(".exe")
                ? name.substring(0, name.length() - 4) : name;
    }
}
