package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.exception.BusinessErrorCode;
import com.bigdata.scriptbox.exception.BusinessException;
import com.bigdata.scriptbox.executor.CommandExecutor;
import com.bigdata.scriptbox.executor.CommandResult;
import com.bigdata.scriptbox.executor.CommandSpec;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.mapper.TenantMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 租户服务。
 *
 * <p>负责租户元数据 + keytab 文件上传。keytab 文件本体落在
 * {@link StoragePathService#keytabsRoot()} 下的受控目录，按
 * {@code tenant_<id>_<uuid>.keytab} 命名，权限收紧为 {@code rw-------}。
 *
 * <p><b>敏感数据约定</b>：keytab 二进制内容<b>绝不</b>写入 H2，仅持久化路径。
 * 日志里也只打印 keytab 路径而非内容。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TenantService {

    private final TenantMapper tenantMapper;
    private final ExecutionHistoryMapper historyMapper;
    private final ScriptBoxProperties props;
    private final StoragePathService storagePathService;
    private final CommandExecutor commandExecutor;

    /** 启动时确保 keytab 根目录存在。 */
    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(storagePathService.keytabsRoot());
    }

    /**
     * 列出全部租户，按 ID 降序。
     */
    public List<Tenant> listAll() {
        return tenantMapper.selectList(new QueryWrapper<Tenant>().orderByDesc("id"));
    }

    /**
     * 按 ID 查询单个租户。
     *
     * @param id 主键
     * @return 实体（可能为 null）
     */
    public Tenant getById(Long id) {
        return tenantMapper.selectById(id);
    }

    /**
     * 新建一个租户。
     *
     * @param tenant 实体
     * @return 持久化后的实体
     */
    public Tenant create(Tenant tenant) {
        if (tenant.getEnabled() == null) tenant.setEnabled(Boolean.TRUE);
        // V3 (PR-1): auth_name UNIQUE 校验。与 principal 一一对应（前端约束同改同存）。
        if (tenant.getAuthName() != null && !tenant.getAuthName().isBlank()) {
            Long dup = countByAuthName(tenant.getAuthName());
            if (dup > 0) {
                throw new BusinessException(BusinessErrorCode.INTERNAL_ERROR,
                        "authName 已存在：" + tenant.getAuthName() + "（认证名称 = Principal 别名，必须全局唯一）");
            }
        }
        // 改了 principal 视为认证已变更：清空 last_test_at
        if (tenant.getAuthName() != null && !tenant.getAuthName().isBlank()) {
            tenant.setLastTestAt(null);
            tenant.setLastTestOk(null);
        }
        LocalDateTime now = LocalDateTime.now();
        tenant.setCreateTime(now);
        tenant.setUpdateTime(now);
        tenantMapper.insert(tenant);
        log.info("新增租户，tenantId={}，code={}", tenant.getId(), tenant.getName());
        return tenant;
    }

    /**
     * 更新一个租户的元数据。
     */
    public Tenant update(Tenant tenant) {
        // V3 (PR-1): principal 或 authName 修改后，标记认证为"待重新测试"（lastTestAt=null）。
        Tenant existing = tenantMapper.selectById(tenant.getId());
        if (existing != null) {
            boolean principalChanged = tenant.getPrincipal() != null
                    && !tenant.getPrincipal().equals(existing.getPrincipal());
            boolean authNameChanged = tenant.getAuthName() != null
                    && !tenant.getAuthName().equals(existing.getAuthName());
            if (principalChanged || authNameChanged) {
                tenant.setLastTestAt(null);
                tenant.setLastTestOk(null);
            }
        }
        // V3 (PR-1): authName 唯一性也按"修改后是否撞名"做应用层预检，
        // 避免 DB 层 DuplicateKeyException 直接穿透到 Controller。
        if (tenant.getAuthName() != null && !tenant.getAuthName().isBlank()) {
            Long dup = tenantMapper.selectCount(
                    new QueryWrapper<Tenant>()
                            .eq("auth_name", tenant.getAuthName())
                            .ne("id", tenant.getId()));
            if (dup > 0) {
                throw new BusinessException(BusinessErrorCode.INTERNAL_ERROR,
                        "authName 已存在：" + tenant.getAuthName() + "（认证名称 = Principal 别名，必须全局唯一）");
            }
        }
        tenant.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(tenant);
        return tenantMapper.selectById(tenant.getId());
    }

    /**
     * V3 (PR-1): 按 authName 查重（不含自己）。
     */
    private Long countByAuthName(String authName) {
        return tenantMapper.selectCount(new QueryWrapper<Tenant>().eq("auth_name", authName));
    }

    /**
     * V3 (PR-1): 把"认证为待重新测试"标记直接写库。由前端在用户改 principal/keytab 后调用。
     */
    public Tenant markAuthStale(Long id) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) return null;
        t.setLastTestAt(null);
        t.setLastTestOk(null);
        t.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(t);
        log.info("租户认证标记为待重新测试，tenantId={}", id);
        return t;
    }

    /**
     * V3 (PR-1): 记录一次认证测试结果。testConnectivity 成功后由 Controller 调用。
     */
    public Tenant recordTestResult(Long id, boolean ok) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) return null;
        t.setLastTestAt(LocalDateTime.now());
        t.setLastTestOk(ok);
        t.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(t);
        return t;
    }

    /**
     * 删除一个租户；同时清理其 keytab 文件。
     */
    public void delete(Long id) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) return;
        tenantMapper.deleteById(id);
        if (t.getKeytabPath() != null) {
            try {
                Files.deleteIfExists(Paths.get(t.getKeytabPath()));
            } catch (IOException ignored) { }
        }
        log.info("删除租户，tenantId={}", id);
    }

    /**
     * 启用 / 停用一个租户。
     */
    public Tenant setEnabled(Long id, boolean enabled) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) return null;
        t.setEnabled(enabled);
        t.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(t);
        log.info("租户启停切换，tenantId={}，enabled={}", id, enabled);
        return t;
    }

    /**
     * 保存上传的 keytab 到受控目录，落盘路径返回。
     *
     * <p>校验项：
     * <ul>
     *   <li>文件非空；</li>
     *   <li>扩展名为 {@code .keytab}；</li>
     *   <li>路径必须在 {@link StoragePathService#keytabsRoot()} 之下。</li>
     * </ul>
     *
     * <p>写入后调用 {@link Files#setPosixFilePermissions} 收紧权限为
     * {@code rw-------}，避免被同机其他用户读取。
     */
    public String saveKeytab(Long tenantId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(BusinessErrorCode.FILE_INPUT_INVALID, "keytab file is empty");
        }
        String original = file.getOriginalFilename() == null ? "keytab" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".keytab")) {
            throw new BusinessException(BusinessErrorCode.FILE_INPUT_INVALID, "only .keytab files are allowed");
        }
        Path target = storagePathService.keytabPath(tenantId, UUID.randomUUID().toString());
        // 路径安全：阻断恶意 / 拼错的 keytab 路径越出受控根
        storagePathService.assertInside(target, storagePathService.keytabsRoot(), "keytab");
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        return target.toAbsolutePath().toString();
    }

    /**
     * 把 keytab 路径绑定到租户（数据库写入）。
     */
    public Tenant attachKeytab(Long tenantId, String keytabPath) {
        Tenant t = tenantMapper.selectById(tenantId);
        if (t == null) throw new BusinessException(BusinessErrorCode.TENANT_NOT_FOUND,
                "tenant not found: " + tenantId);
        t.setKeytabPath(keytabPath);
        t.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(t);
        return t;
    }

    /**
     * V2: 引用此租户的执行历史行数，用于删除确认对话框展示爆炸半径。
     *
     * @param id 租户 ID
     * @return 键为 {@code historyCount} 的计数
     */
    public Map<String, Long> relatedCounts(Long id) {
        Map<String, Long> out = new HashMap<>();
        out.put("historyCount",
                historyMapper.selectCount(new QueryWrapper<ExecutionHistory>().eq("tenant_id", id)));
        return out;
    }

    /**
     * 租户连通性测试：mock 模式返回模拟输出；真实模式跑
     * {@code kinit -kt <keytab> <principal>} + {@code klist}。
     *
     * <p>历史上这段进程编排写在 {@code TenantController#test} 里，用的是
     * {@code new ProcessBuilder(...)} + 无超时 {@code waitFor()} + {@code readAllBytes()}，
     * 既让 Controller 越界触碰进程层，又可能在 KDC 无响应时挂死 Tomcat 线程。
     * 现在统一走 {@link CommandExecutor}（硬超时 + 排空 + 进程树回收 + 不占并发槽位）。
     *
     * <p>V3 (PR-1): 测试成功后写 last_test_at / last_test_ok；失败时也写 ok=false。
     *
     * @param id 租户 ID
     * @return 结果视图；租户不存在时返回 {@code null}
     */
    public TenantConnectivity testConnectivity(Long id) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) return null;

        if (props.isMock()) {
            // mock 模式下避免触碰真实 kinit；给一份假输出
            log.info("租户连通性测试 (mock)，tenant={}", t.getName());
            TenantConnectivity res = TenantConnectivity.mock(t);
            recordTestResult(id, true);
            return res;
        }

        if (t.getKeytabPath() == null || t.getKeytabPath().isBlank()) {
            throw new BusinessException(BusinessErrorCode.TENANT_NOT_FOUND,
                    "keytab not configured for tenant");
        }
        if (!Files.exists(Paths.get(t.getKeytabPath()))) {
            throw new BusinessException(BusinessErrorCode.INTERNAL_IO_ERROR,
                    "keytab file missing: " + t.getKeytabPath());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "real");
        try {
            // streamOutput=true：kinit / klist 只看退出码，输出直接透传日志，不落盘。
            CommandResult kinit = commandExecutor.exec(new CommandSpec(
                    List.of(props.getKinitExecutable(), "-kt", t.getKeytabPath(), t.getPrincipal()),
                    null, Map.of(), null, null,
                    props.getCommandTimeoutSeconds(), "kinit", 0L, true));
            result.put("kinitExit", kinit.exitCode());
            if (!kinit.ok()) {
                result.put("ok", false);
                log.warn("租户连通性测试失败：kinit exit={} timeout={}，tenant={}",
                        kinit.exitCode(), kinit.timeout(), t.getName());
                recordTestResult(id, false);
                return new TenantConnectivity(t, false, result);
            }
            CommandResult klist = commandExecutor.exec(CommandSpec.of(
                    List.of(props.getKlistExecutable()), props.getCommandTimeoutSeconds(), "klist"));
            result.put("ok", true);
            log.info("租户连通性测试通过，tenant={}", t.getName());
            recordTestResult(id, true);
            return new TenantConnectivity(t, true, result);
        } catch (IOException ioe) {
            log.warn("租户连通性测试异常，tenant={}，error={}", t.getName(), ioe.getMessage());
            recordTestResult(id, false);
            throw new IllegalStateException("test failed: " + ioe.getMessage(), ioe);
        }
    }

    /** 租户连通性测试结果视图。 */
    public record TenantConnectivity(Tenant tenant, boolean ok, Map<String, Object> details) {

        /** mock 模式下的固定输出（不触碰真实 kinit）。 */
        static TenantConnectivity mock(Tenant t) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "mock");
            result.put("ok", true);
            result.put("stdout", "Mock kinit OK for principal " + t.getPrincipal()
                    + "\nMock klist:\n  Ticket cache: FILE:/tmp/krb5cc_mock\n  Default principal: "
                    + t.getPrincipal() + "\n");
            result.put("stderr", "");
            return new TenantConnectivity(t, true, result);
        }
    }
}