package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.Tenant;
import com.bigdata.scriptbox.mapper.TenantMapper;
import jakarta.annotation.PostConstruct;
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

@Service
public class TenantService {

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private ScriptBoxProperties props;

    @Autowired
    private StoragePathService storagePathService;

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(storagePathService.keytabsRoot());
    }

    public List<Tenant> listAll() {
        return tenantMapper.selectList(new QueryWrapper<Tenant>().orderByDesc("id"));
    }

    public Tenant getById(Long id) {
        return tenantMapper.selectById(id);
    }

    public Tenant create(Tenant tenant) {
        if (tenant.getEnabled() == null) tenant.setEnabled(Boolean.TRUE);
        LocalDateTime now = LocalDateTime.now();
        tenant.setCreateTime(now);
        tenant.setUpdateTime(now);
        tenantMapper.insert(tenant);
        return tenant;
    }

    public Tenant update(Tenant tenant) {
        tenant.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(tenant);
        return tenantMapper.selectById(tenant.getId());
    }

    public void delete(Long id) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) return;
        tenantMapper.deleteById(id);
        if (t.getKeytabPath() != null) {
            try {
                Files.deleteIfExists(Paths.get(t.getKeytabPath()));
            } catch (IOException ignored) { }
        }
    }

    public Tenant setEnabled(Long id, boolean enabled) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) return null;
        t.setEnabled(enabled);
        t.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(t);
        return t;
    }

    /**
     * Store uploaded keytab to keytabsDir; return absolute path.
     * Keytab binary content is NEVER stored in H2.
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
        storagePathService.assertInside(target, storagePathService.keytabsRoot(), "keytab");
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        return target.toAbsolutePath().toString();
    }

    public Tenant attachKeytab(Long tenantId, String keytabPath) {
        Tenant t = tenantMapper.selectById(tenantId);
        if (t == null) throw new IllegalArgumentException("tenant not found: " + tenantId);
        t.setKeytabPath(keytabPath);
        t.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(t);
        return t;
    }
}