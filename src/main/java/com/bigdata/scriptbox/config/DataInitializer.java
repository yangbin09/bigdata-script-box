package com.bigdata.scriptbox.config;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds a default mock tenant and the five mock scripts so that the app is usable
 * out of the box. Idempotent — only runs when tables are empty.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired private TenantService tenantService;
    @Autowired private ScriptService scriptService;
    @Autowired private ScriptBoxProperties props;

    @Override
    public void run(String... args) throws Exception {
        // Always re-copy the bundled mock scripts into data/scripts/mock_*.sh so the system
        // is reproducible across resets. These are treated as built-in fixtures.
        copyBundledMockScripts();

        if (tenantService.listAll().isEmpty()) {
            Tenant t = new Tenant();
            t.setName("mock-hive");
            t.setPrincipal("hive@EXAMPLE.COM");
            t.setKeytabPath(null);
            t.setDefaultDatabase("default");
            t.setDescription("内置 Mock 租户，开发模式专用。");
            t.setEnabled(true);
            tenantService.create(t);
            log.info("seeded mock tenant");
        }

        if (scriptService.listAll().isEmpty()) {
            registerScript("success", "成功示例", "Mock",
                    "演示脚本成功执行，输出参数内容。", "success.sh",
                    60, "name", "名称", "text", "world", null, false);
            registerScript("failed", "失败示例", "Mock",
                    "演示脚本失败：stderr 输出错误，exit 1。", "failed.sh",
                    60);
            registerScript("stderr-mix", "stderr 示例", "Mock",
                    "同时输出 stdout 和 stderr。", "stderr.sh",
                    60, "message", "消息", "text", "hello", null, false);
            registerScript("timeout", "超时示例", "Mock",
                    "持续 sleep 用于测试 timeout。", "timeout.sh",
                    3);
            registerScript("large-output", "大量日志示例", "Mock",
                    "输出大量日志行，验证 stdout/stderr 不会阻塞。", "large-output.sh",
                    60, "lines", "行数", "number", "5000", null, false);
            log.info("seeded 5 mock scripts");
        }
    }

    private void registerScript(String name, String displayName, String category, String desc,
                                String file, int timeout) throws IOException {
        registerScript(name, displayName, category, desc, file, timeout, null, null, null, null, null, false);
    }

    private void registerScript(String name, String displayName, String category, String desc,
                                String file, int timeout,
                                String paramName, String paramLabel, String paramType,
                                String paramDefault, String paramOptions, boolean paramRequired) throws IOException {
        Path src = Paths.get("mock-scripts").resolve(file);
        if (!Files.exists(src)) {
            log.warn("mock script missing: {}", src);
            return;
        }
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(displayName);
        s.setCategory(category);
        s.setDescription(desc);
        s.setTimeoutSeconds(timeout);
        s.setEnabled(true);
        // Use the bundled file as the MultipartFile source.
        byte[] body = Files.readAllBytes(src);
        org.springframework.web.multipart.MultipartFile mf = new InMemoryMultipartFile(
                file, file, "application/x-sh", body);
        Script saved = scriptService.create(s, mf);

        if (paramName != null) {
            ScriptParam p = new ScriptParam();
            p.setName(paramName);
            p.setLabel(paramLabel);
            p.setType(paramType);
            p.setDefaultValue(paramDefault);
            p.setOptions(paramOptions);
            p.setRequired(paramRequired);
            p.setSortOrder(0);
            List<ScriptParam> list = new ArrayList<>();
            list.add(p);
            scriptService.replaceParams(saved.getId(), list);
        }
    }

    private void copyBundledMockScripts() {
        // mock scripts live under data/scripts/mock_*.sh; copy them at first start so that
        // the executor always has them under the configured scripts dir.
        // We achieve this by re-registering them — but since register runs only when empty,
        // we rely on the seed above. This method is a placeholder for future expansion.
    }
}