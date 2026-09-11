package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.mapper.TenantMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
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
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private ScriptBoxProperties props;

    @Autowired
    private StoragePathService storagePathService;

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
        tenant.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(tenant);
        return tenantMapper.selectById(tenant.getId());
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
            throw new IllegalArgumentException("keytab file is empty");
        }
        String original = file.getOriginalFilename() == null ? "keytab" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".keytab")) {
            throw new IllegalArgumentException("only .keytab files are allowed");
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
        if (t == null) throw new IllegalArgumentException("tenant not found: " + tenantId);
        t.setKeytabPath(keytabPath);
        t.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(t);
        return t;
    }
}