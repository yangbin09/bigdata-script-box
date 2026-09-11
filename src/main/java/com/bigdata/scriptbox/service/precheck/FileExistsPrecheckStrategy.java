package com.bigdata.scriptbox.service.precheck;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 文件存在检查：检查列出的文件路径是否真实存在。
 *
 * <p>对应 JSON key：{@code "files"}，值为字符串数组。
 */
@Component
public class FileExistsPrecheckStrategy implements PrecheckStrategy {

    private static final String TYPE = "files";

    private final ObjectMapper mapper;

    public FileExistsPrecheckStrategy(ObjectMapper mapper) {
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
    public CheckOutcome check(String path, Script script, Tenant tenant) {
        if (path == null || path.isBlank())
            return CheckOutcome.fail(path, "empty path");
        if (!Files.exists(Paths.get(path)))
            return CheckOutcome.fail(path, "file does not exist");
        return CheckOutcome.pass(path, "exists");
    }
}
