package com.bigdata.scriptbox.service.precheck;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;

/**
 * 目录可写检查：检查列出的目录路径存在且可写，不存在则尝试创建。
 *
 * <p>对应 JSON key：{@code "writableDirectories"}，值为字符串数组。
 * 项目列表的解析由 {@link AbstractJsonListPrecheckStrategy} 统一提供。
 */
@Component
public class DirectoryWritablePrecheckStrategy extends AbstractJsonListPrecheckStrategy {

    public DirectoryWritablePrecheckStrategy(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public String configKey() {
        return "writableDirectories";
    }

    @Override
    public CheckOutcome check(String dir, Script script, Tenant tenant) {
        if (dir == null || dir.isBlank())
            return CheckOutcome.fail(dir, "empty path");
        java.nio.file.Path p = java.nio.file.Paths.get(dir);
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
