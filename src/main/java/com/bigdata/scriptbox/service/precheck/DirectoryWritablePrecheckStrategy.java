package com.bigdata.scriptbox.service.precheck;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 目录可写检查：检查列出的目录路径存在且可写，不存在则尝试创建。
 *
 * <p>对应 JSON key：{@code "writableDirectories"}，值为字符串数组。
 */
@Component
public class DirectoryWritablePrecheckStrategy implements PrecheckStrategy {

    private static final String TYPE = "writableDirectories";

    private final ObjectMapper mapper;

    public DirectoryWritablePrecheckStrategy(ObjectMapper mapper) {
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
    public CheckOutcome check(String dir, Script script, Tenant tenant) {
        if (dir == null || dir.isBlank())
            return CheckOutcome.fail(dir, "empty path");
        java.nio.file.Path p = Paths.get(dir);
        try {
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (IOException ex) {
            return CheckOutcome.fail(dir, "cannot create: " + ex.getMessage());
        }
        if (!Files.isDirectory(p))
            return CheckOutcome.fail(dir, "not a directory");
        if (!Files.isWritable(p))
            return CheckOutcome.fail(dir, "not writable");
        return CheckOutcome.pass(dir, "writable");
    }
}
