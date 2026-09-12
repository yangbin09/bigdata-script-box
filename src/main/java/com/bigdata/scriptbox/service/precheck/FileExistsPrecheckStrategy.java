package com.bigdata.scriptbox.service.precheck;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 文件存在检查：检查列出的文件路径是否真实存在。
 *
 * <p>对应 JSON key：{@code "files"}，值为字符串数组。
 * 项目列表的解析由 {@link AbstractJsonListPrecheckStrategy} 统一提供。
 */
@Component
public class FileExistsPrecheckStrategy extends AbstractJsonListPrecheckStrategy {

    public FileExistsPrecheckStrategy(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public String configKey() {
        return "files";
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
