package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptVersion;
import com.bigdata.scriptbox.mapper.ScriptMapper;
import com.bigdata.scriptbox.mapper.ScriptVersionMapper;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Append-only history of script body changes. rollback does NOT delete the
 * current version; it creates a new version whose content equals the older
 * version. Old rows are kept indefinitely.
 */
@Service
public class ScriptVersionService {

    @Autowired private ScriptVersionMapper versionMapper;
    @Autowired private ScriptMapper scriptMapper;
    @Autowired private ScriptBoxProperties props;
    @Autowired private StoragePathService storagePathService;

    /**
     * Take a snapshot of the script's CURRENT body. Called after create /
     * body-save / import-override. versionNo is 1-based monotonic per script.
     */
    @Transactional
    public ScriptVersion snapshot(Long scriptId, String remark) throws IOException {
        Script s = scriptMapper.selectById(scriptId);
        if (s == null) throw new IllegalArgumentException("script not found: " + scriptId);
        String body = readBodyFromDisk(s);
        ScriptVersion latest = versionMapper.selectMaxVersion(scriptId);
        int nextNo = (latest == null) ? 1 : (latest.getVersionNo() + 1);
        ScriptVersion v = new ScriptVersion();
        v.setScriptId(scriptId);
        v.setVersionNo(nextNo);
        v.setScriptContent(body == null ? "" : body);
        v.setRemark(remark == null ? "" : remark);
        v.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(v);
        return v;
    }

    public List<ScriptVersion> listByScript(Long scriptId) {
        return versionMapper.selectByScriptId(scriptId);
    }

    public ScriptVersion get(Long scriptId, Integer versionNo) {
        return versionMapper.selectOne(new QueryWrapper<ScriptVersion>()
                .eq("script_id", scriptId).eq("version_no", versionNo));
    }

    /**
     * Roll back to an older version: write its content to disk as the current body
     * AND create a new version row (versionNo = max+1) so history stays append-only.
     */
    @Transactional
    public ScriptVersion rollback(Long scriptId, Integer versionNo) throws IOException {
        ScriptVersion target = get(scriptId, versionNo);
        if (target == null) throw new IllegalArgumentException("version not found: v" + versionNo);
        Script s = scriptMapper.selectById(scriptId);
        if (s == null) throw new IllegalArgumentException("script not found");
        Path file = scriptFilePath(scriptId);
        Files.createDirectories(file.getParent());
        Files.writeString(file, target.getScriptContent() == null ? "" : target.getScriptContent(),
                StandardCharsets.UTF_8);
        s.setUpdateTime(LocalDateTime.now());
        scriptMapper.updateById(s);
        return snapshot(scriptId, "rollback to v" + versionNo);
    }

    public void deleteAllForScript(Long scriptId) {
        versionMapper.delete(new QueryWrapper<ScriptVersion>().eq("script_id", scriptId));
    }

    /** Same on-disk layout as ScriptService#persistScriptBody. */
    private Path scriptFilePath(Long scriptId) {
        return storagePathService.scriptFilePath(scriptId);
    }

    private String readBodyFromDisk(Script s) throws IOException {
        if (s.getScriptPath() == null) return "";
        Path p = Paths.get(s.getScriptPath());
        if (!Files.exists(p)) return "";
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}