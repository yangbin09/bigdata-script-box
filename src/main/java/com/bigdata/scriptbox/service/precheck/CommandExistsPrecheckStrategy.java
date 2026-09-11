package com.bigdata.scriptbox.service.precheck;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 命令存在检查：检查命令是否在 PATH 中，或以绝对路径给出时是否可执行。
 *
 * <p>对应 JSON key：{@code "commands"}，值为字符串数组。
 */
@Component
public class CommandExistsPrecheckStrategy implements PrecheckStrategy {

    private static final String TYPE = "commands";

    private final ObjectMapper mapper;

    public CommandExistsPrecheckStrategy(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<String> items(Script script, Tenant tenant) {
        String json = script.getPrecheckConfigJson();
        if (json == null || json.isBlank()) return List.of();
        try {
            Map<String, Object> cfg = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            Object raw = cfg.get(TYPE);
            if (raw instanceof List<?> list) {
                return list.stream().map(Object::toString).toList();
            }
        } catch (Exception ignored) {
            // 配置 JSON 解析失败统一由 PrecheckService 顶层处理
        }
        return List.of();
    }

    @Override
    public CheckOutcome check(String cmd, Script script, Tenant tenant) {
        if (cmd == null || cmd.isBlank())
            return CheckOutcome.fail(cmd, "empty command name");
        Path p = Paths.get(cmd);
        if (p.isAbsolute()) {
            if (Files.exists(p) && Files.isExecutable(p))
                return CheckOutcome.pass(cmd, "absolute path exists and executable");
            return CheckOutcome.fail(cmd, "not executable");
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) pathEnv = "/usr/local/bin:/usr/bin:/bin";
        for (String dir : pathEnv.split(":")) {
            Path candidate = Paths.get(dir, cmd);
            if (Files.exists(candidate) && Files.isExecutable(candidate))
                return CheckOutcome.pass(cmd, "found in " + dir);
        }
        return CheckOutcome.fail(cmd, "not found in PATH");
    }
}
