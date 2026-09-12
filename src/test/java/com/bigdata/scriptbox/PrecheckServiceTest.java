package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.model.ExecutionStatus;
import com.bigdata.scriptbox.service.PrecheckService;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrecheckServiceTest extends BaseIntegrationTest {

    @Autowired private ScriptService scriptService;
    @Autowired private TenantService tenantService;
    @Autowired private ScriptExecutor executor;
    @Autowired private PrecheckService precheckService;
    @Autowired private ObjectMapper mapper;

    private Long seedScript(String name, String precheckJson) throws IOException {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(name);
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        s.setPrecheckConfigJson(precheckJson);
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", name + ".sh", "application/x-sh",
                "#!/bin/bash\necho hi\n".getBytes(StandardCharsets.UTF_8)));
        // create() 不回填 precheck 配置；用 Service 的意图方法再写一次
        //（不再通过 getMapper() 泄漏持久层）
        scriptService.savePrecheckConfig(saved.getId(), precheckJson);
        return saved.getId();
    }

    private Long seedTenant(String name, boolean enabled) {
        Tenant t = new Tenant();
        t.setName(name);
        t.setPrincipal(name + "@EXAMPLE.COM");
        t.setEnabled(enabled);
        return tenantService.create(t).getId();
    }

    /**
     * 构造 {@code {"key": [items...]}} 形态的配置。
     *
     * <p>用 ObjectMapper 序列化而不是拼字符串：测试里的路径是平台相关的
     * （Windows 上含反斜杠），手工拼 JSON 会产出非法转义。
     */
    private String jsonList(String key, String... items) throws Exception {
        return mapper.writeValueAsString(Map.of(key, List.of(items)));
    }

    /** 平台无关的临时目录，替代历史上写死的 {@code /tmp}。 */
    private String tempDir() {
        return System.getProperty("java.io.tmpdir");
    }

    @Test
    void noConfigMeansSkipped() throws Exception {
        Long sid = seedScript("pc-skip", null);
        Script script = scriptService.getById(sid);
        Tenant tenant = tenantService.getById(seedTenant("pc-t1", true));
        PrecheckService.PrecheckReport out = precheckService.run(script, tenant);
        assertTrue(out.ok());
        assertTrue(out.skipped());
        assertEquals("no precheck configured", out.message());
        assertTrue(out.results().isEmpty());
    }

    @Test
    void kerberosMissingPrincipalFails() throws Exception {
        Long sid = seedScript("pc-krb", "{\"kerberos\":true}");
        Script script = scriptService.getById(sid);
        Tenant tenant = new Tenant();
        tenant.setName("pc-no-principal");
        tenant.setPrincipal(""); // empty
        tenant.setEnabled(true);
        Long tid = tenantService.create(tenant).getId();

        PrecheckService.PrecheckReport out =
                precheckService.run(script, tenantService.getById(tid));
        assertFalse(out.ok());
        assertFalse(out.results().isEmpty());
        // 显示名由策略自己提供（不再是 PrecheckService 里的 kerberos 特例分支），
        // 且不带 :item 后缀，保持与历史 UI 兼容
        assertEquals("Kerberos", out.results().get(0).name());
        assertFalse(out.results().get(0).ok());
    }

    @Test
    void commandExistsInPath() throws Exception {
        Long sid = seedScript("pc-cmd-ok", jsonList("commands", "bash"));
        Script script = scriptService.getById(sid);
        Tenant tenant = tenantService.getById(seedTenant("pc-t2", true));
        PrecheckService.PrecheckReport out = precheckService.run(script, tenant);
        assertTrue(out.ok(), () -> "expected ok, results=" + out.results());
    }

    @Test
    void commandNotInPathFails() throws Exception {
        Long sid = seedScript("pc-cmd-bad",
                jsonList("commands", "this-command-does-not-exist-12345"));
        Script script = scriptService.getById(sid);
        Tenant tenant = tenantService.getById(seedTenant("pc-t3", true));
        PrecheckService.PrecheckReport out = precheckService.run(script, tenant);
        assertFalse(out.ok());
        // 展示名格式：<configKey>:<item>
        assertTrue(out.results().get(0).name().startsWith("commands:"),
                out.results().get(0).name());
    }

    @Test
    void fileExists() throws Exception {
        Long sid = seedScript("pc-file-ok", jsonList("files", tempDir()));
        Script script = scriptService.getById(sid);
        Tenant tenant = tenantService.getById(seedTenant("pc-t4", true));
        PrecheckService.PrecheckReport out = precheckService.run(script, tenant);
        assertTrue(out.ok(), () -> "expected ok, results=" + out.results());
    }

    @Test
    void missingFileFails() throws Exception {
        Long sid = seedScript("pc-file-bad",
                jsonList("files", tempDir() + "/no/such/path/should/exist/12345"));
        Script script = scriptService.getById(sid);
        Tenant tenant = tenantService.getById(seedTenant("pc-t5", true));
        PrecheckService.PrecheckReport out = precheckService.run(script, tenant);
        assertFalse(out.ok());
    }

    @Test
    void directoryWritable() throws Exception {
        Long sid = seedScript("pc-dir", jsonList("writableDirectories", tempDir()));
        Script script = scriptService.getById(sid);
        Tenant tenant = tenantService.getById(seedTenant("pc-t6", true));
        PrecheckService.PrecheckReport out = precheckService.run(script, tenant);
        assertTrue(out.ok(), () -> "expected ok, results=" + out.results());
    }

    @Test
    void invalidConfigJsonFails() throws Exception {
        Long sid = seedScript("pc-bad-json", "{ this is not json");
        Script script = scriptService.getById(sid);
        Tenant tenant = tenantService.getById(seedTenant("pc-t7", true));
        PrecheckService.PrecheckReport out = precheckService.run(script, tenant);
        assertFalse(out.ok());
        assertFalse(out.skipped());
        assertTrue(out.message().startsWith("invalid precheck config:"), out.message());
    }

    @Test
    void executeShortCircuitsOnPrecheckFailure() throws Exception {
        Long sid = seedScript("pc-exec-bad", jsonList("commands", "missing-tool-xyz"));
        Long tid = seedTenant("pc-t-exec", true);
        ExecutionRequest req = new ExecutionRequest();
        req.setScriptId(sid);
        req.setTenantId(tid);
        ExecutionHistory h = executor.execute(req);
        assertFalse(h.getSuccess());
        assertEquals(ExecutionStatus.PRECHECK_FAILED.name(), h.getStatus());
        assertEquals(-1, h.getExitCode());
    }
}
