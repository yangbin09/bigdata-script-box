package com.bigdata.scriptbox.config;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.ScriptTemplate;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.ScriptTemplateService;
import com.bigdata.scriptbox.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用启动时的示例数据初始化器。
 *
 * <p>职责：
 * <ol>
 *   <li>把 {@code mock-scripts/} 内置的脚本复制到受控脚本目录；</li>
 *   <li>若租户表为空，插入一个内置的 Mock 租户；</li>
 *   <li>若脚本表为空，注册 5 个内置示例脚本；</li>
 *   <li>若模板表为空，注入 5 个内置脚本模板（plain-shell / kerberos / spark-sql / hdfs / yarn）。</li>
 * </ol>
 *
 * <p>所有初始化操作都是「幂等」的：仅在对应表为空时执行，因此用户后续添加的数据
 * 不会被反复重置。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final TenantService tenantService;
    private final ScriptService scriptService;
    private final ScriptTemplateService templateService;
    private final ScriptBoxProperties props;

    /**
     * Spring Boot 启动完成后调用，执行示例数据注入。
     *
     * @param args 命令行参数（本实现未使用）
     * @throws Exception 读取内置脚本或创建实体失败时抛出
     */
    @Override
    public void run(String... args) throws Exception {
        // 始终把内置 mock 脚本同步到受控脚本目录，保证执行器拿得到它们
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
            log.info("已初始化 Mock 租户 tenant=mock-hive");
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
            log.info("已初始化 5 个内置 Mock 脚本");
        }

        // V2: 内置脚本模板。仅在模板表为空时执行，后续用户扩展不会被覆盖
        if (templateService.listAll().isEmpty()) {
            seedTemplates();
            log.info("已初始化 {} 个内置脚本模板", templateService.listAll().size());
        }
    }

    /**
     * 注入 5 个内置脚本模板。
     */
    private void seedTemplates() {
        // 1) 普通 Shell：仅 echo + 接收一个 name 参数
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

        // 2) Kerberos：先 kinit，再执行用户指定命令，常用于 Spark/Hive 提交
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

        // 3) Spark SQL：调用 spark-sql 跑一段 SQL，结果落到 stdout
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

        // 4) HDFS：对 HDFS 路径执行 ls/cat/du 等操作
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

        // 5) YARN：调用 yarn jar 提交一个 MR 任务
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

    /**
     * 注册或更新一个内置模板。
     *
     * @param code 模板编码（唯一主键）
     * @param name 显示名
     * @param category 分类
     * @param desc 描述
     * @param content 脚本正文
     * @param paramsJson 参数 JSON（可为 null）
     * @param sortOrder 排序
     */
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

    /**
     * 注册一个不带参数的 mock 脚本。
     */
    private void registerScript(String name, String displayName, String category, String desc,
                                String file, int timeout) throws IOException {
        registerScript(name, displayName, category, desc, file, timeout, null, null, null, null, null, false);
    }

    /**
     * 注册一个内置 mock 脚本及其参数。
     *
     * @param name 脚本技术名
     * @param displayName 显示名
     * @param category 分类
     * @param desc 描述
     * @param file mock-scripts 目录下的文件名
     * @param timeout 超时（秒）
     * @param paramName 参数名（可为 null）
     * @param paramLabel 参数显示名（可为 null）
     * @param paramType 参数类型（可为 null）
     * @param paramDefault 参数默认值（可为 null）
     * @param paramOptions 参数可选值（逗号分隔，可为 null）
     * @param paramRequired 是否必填
     */
    private void registerScript(String name, String displayName, String category, String desc,
                                String file, int timeout,
                                String paramName, String paramLabel, String paramType,
                                String paramDefault, String paramOptions, boolean paramRequired) throws IOException {
        Path src = Paths.get("mock-scripts").resolve(file);
        if (!Files.exists(src)) {
            log.warn("内置 mock 脚本缺失: {}", src);
            return;
        }
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(displayName);
        s.setCategory(category);
        s.setDescription(desc);
        s.setTimeoutSeconds(timeout);
        s.setEnabled(true);
        // 用内置文件作为 MultipartFile 数据源，避免走 HTTP 上传
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

    /**
     * 把内置 mock 脚本复制到受控脚本目录。
     *
     * <p>目前为占位实现：示例脚本在 register 时通过 {@link InMemoryMultipartFile}
     * 走正常的入库流程，因此不需要额外的磁盘同步。保留方法以便未来扩展。
     */
    private void copyBundledMockScripts() {
        // mock 脚本实际写入 ./data/scripts/mock_*.sh；当前 register 流程已经覆盖。
        // 保留此方法以备未来扩展（例如按需热同步 mock 脚本）。
    }
}