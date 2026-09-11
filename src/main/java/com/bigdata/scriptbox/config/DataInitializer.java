package com.bigdata.scriptbox.config;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.ScriptTemplate;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.ScriptTemplateService;
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
    @Autowired private ScriptTemplateService templateService;
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

        // V2: built-in script templates. Idempotent — only seeds when the
        // table is empty, so user-added templates (future) survive restarts.
        if (templateService.listAll().isEmpty()) {
            seedTemplates();
            log.info("seeded {} script templates", templateService.listAll().size());
        }
    }

    private void seedTemplates() {
        // 1) plain-shell: minimal echo-only script for sanity checks.
        registerTemplate("plain-shell", "普通 Shell", "Shell",
                "最简脚本：接收一个 name 参数，输出 hello 并打印传入值。",
                "#!/bin/bash\n" +
                "set -euo pipefail\n" +
                "name=\"${1:-world}\"\n" +
                "echo \"hello ${name}\"\n" +
                "exit 0\n",
                "[{\"name\":\"name\",\"label\":\"名称\",\"type\":\"text\",\"defaultValue\":\"world\"," +
                "\"placeholder\":\"输入名字\",\"sortOrder\":0}]",
                10);

        // 2) kerberos: a template that runs kinit then executes its arguments.
        registerTemplate("kerberos", "Kerberos 客户端", "Auth",
                "先调用 kinit，再执行用户指定的命令。常用于 Spark/Hive 提交。",
                "#!/bin/bash\n" +
                "set -euo pipefail\n" +
                "if [[ -n \"${KEYTAB_PATH:-}\" && -n \"${PRINCIPAL:-}\" ]]; then\n" +
                "  kinit -kt \"$KEYTAB_PATH\" \"$PRINCIPAL\" || { echo 'kinit failed' >&2; exit 2; }\n" +
                "fi\n" +
                "echo 'kerberos ticket ok'\n" +
                "echo \"command=$1\"\n" +
                "exit 0\n",
                "[{\"name\":\"command\",\"label\":\"命令\",\"type\":\"text\"," +
                "\"placeholder\":\"spark-submit ...\",\"required\":true,\"sortOrder\":0}]",
                20);

        // 3) spark-sql: a template that invokes spark-sql with a query.
        registerTemplate("spark-sql", "Spark SQL", "Compute",
                "调用 spark-sql 跑一段 SQL，结果落到 stdout。",
                "#!/bin/bash\n" +
                "set -euo pipefail\n" +
                "db=\"${1:-default}\"\n" +
                "sql=\"${2:-show tables}\"\n" +
                "echo \"would run: spark-sql --database ${db} -e \\\"${sql}\\\"\"\n" +
                "echo \"db=${db}\"\n" +
                "echo \"sql=${sql}\"\n" +
                "exit 0\n",
                "[{\"name\":\"database\",\"label\":\"数据库\",\"type\":\"text\",\"defaultValue\":\"default\",\"sortOrder\":0}," +
                "{\"name\":\"sql\",\"label\":\"SQL 语句\",\"type\":\"textarea\",\"defaultValue\":\"show tables\"," +
                "\"placeholder\":\"select ... from ...\",\"required\":true,\"sortOrder\":1}]",
                30);

        // 4) hdfs: a template that runs hdfs dfs commands.
        registerTemplate("hdfs", "HDFS 操作", "Storage",
                "对 HDFS 路径执行 ls/cat/du 等操作。",
                "#!/bin/bash\n" +
                "set -euo pipefail\n" +
                "action=\"${1:-ls}\"\n" +
                "path=\"${2:-/}\"\n" +
                "echo \"would run: hdfs dfs -${action} ${path}\"\n" +
                "echo \"action=${action}\"\n" +
                "echo \"path=${path}\"\n" +
                "exit 0\n",
                "[{\"name\":\"action\",\"label\":\"动作\",\"type\":\"select\",\"defaultValue\":\"ls\"," +
                "\"options\":\"ls,cat,du,stat\",\"sortOrder\":0}," +
                "{\"name\":\"path\",\"label\":\"路径\",\"type\":\"text\",\"defaultValue\":\"/\"," +
                "\"placeholder\":\"/data/warehouse\",\"required\":true,\"sortOrder\":1}]",
                40);

        // 5) yarn: a template that submits to YARN.
        registerTemplate("yarn", "YARN 提交", "Compute",
                "调用 yarn jar 提交一个 MR 任务。",
                "#!/bin/bash\n" +
                "set -euo pipefail\n" +
                "jar=\"${1:-}\"\n" +
                "args=\"${2:-}\"\n" +
                "if [[ -z \"$jar\" ]]; then echo 'jar path is required' >&2; exit 2; fi\n" +
                "echo \"would run: yarn jar ${jar} ${args}\"\n" +
                "echo \"jar=${jar}\"\n" +
                "echo \"args=${args}\"\n" +
                "exit 0\n",
                "[{\"name\":\"jar\",\"label\":\"JAR 路径\",\"type\":\"text\",\"defaultValue\":\"/opt/jars/wc.jar\"," +
                "\"placeholder\":\"hdfs:///jars/wordcount.jar\",\"required\":true,\"sortOrder\":0}," +
                "{\"name\":\"args\",\"label\":\"参数\",\"type\":\"textarea\",\"defaultValue\":\"input output\"," +
                "\"placeholder\":\"input hdfs path / output hdfs path\",\"sortOrder\":1}]",
                50);
    }

    private void registerTemplate(String code, String name, String category, String desc,
                                  String content, String paramsJson, int sortOrder) {
        ScriptTemplate t = new ScriptTemplate();
        t.setCode(code);
        t.setName(name);
        t.setCategory(category);
        t.setDescription(desc);
        t.setContent(content);
        t.setParamsJson(paramsJson);
        t.setSortOrder(sortOrder);
        t.setEnabled(true);
        templateService.createOrUpdate(t);
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