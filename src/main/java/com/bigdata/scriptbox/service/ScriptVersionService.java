package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptVersion;
import com.bigdata.scriptbox.exception.BusinessErrorCode;
import com.bigdata.scriptbox.exception.BusinessException;
import com.bigdata.scriptbox.mapper.ScriptMapper;
import com.bigdata.scriptbox.mapper.ScriptVersionMapper;
import lombok.RequiredArgsConstructor;
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
 * 脚本正文历史快照服务。
 *
 * <p>Append-only：每次保存脚本正文都追加一行版本记录；rollback 也只是把旧
 * 版本内容写到磁盘 + 追加一行新版本行，旧行永远保留。versionNo 从 1 开始按
 * 脚本单调递增。
 */
@Service
@RequiredArgsConstructor
public class ScriptVersionService {

    private final ScriptVersionMapper versionMapper;
    private final ScriptMapper scriptMapper;
    private final ScriptBoxProperties props;
    private final StoragePathService storagePathService;

    /**
     * 给当前脚本正文拍一份快照。版本号在已有最大版本号基础上 +1。
     *
     * @param scriptId 脚本 ID
     * @param remark 备注（"initial" / "body edit" / "rollback to v3" 等）
     * @return 新建的版本记录
     */
    @Transactional
    public ScriptVersion snapshot(Long scriptId, String remark) throws IOException {
        Script s = scriptMapper.selectById(scriptId);
        if (s == null) throw new BusinessException(BusinessErrorCode.SCRIPT_NOT_FOUND,
                "script not found: " + scriptId);
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

    /**
     * 列出某脚本的全部版本（按 version_no 降序）。
     */
    public List<ScriptVersion> listByScript(Long scriptId) {
        return versionMapper.selectByScriptId(scriptId);
    }

    /**
     * 查询某脚本的指定版本号。
     */
    public ScriptVersion get(Long scriptId, Integer versionNo) {
        return versionMapper.selectOne(new QueryWrapper<ScriptVersion>()
                .eq("script_id", scriptId).eq("version_no", versionNo));
    }

    /**
     * 回滚到指定版本：把旧版本内容写到磁盘 + 追加一行新版本（保持 append-only）。
     */
    @Transactional
    public ScriptVersion rollback(Long scriptId, Integer versionNo) throws IOException {
        ScriptVersion target = get(scriptId, versionNo);
        if (target == null) throw new BusinessException(BusinessErrorCode.VERSION_NOT_FOUND,
                "version not found: v" + versionNo);
        Script s = scriptMapper.selectById(scriptId);
        if (s == null) throw new BusinessException(BusinessErrorCode.SCRIPT_NOT_FOUND, "script not found");
        Path file = scriptFilePath(scriptId);
        Files.createDirectories(file.getParent());
        Files.writeString(file, target.getScriptContent() == null ? "" : target.getScriptContent(),
                StandardCharsets.UTF_8);
        s.setUpdateTime(LocalDateTime.now());
        scriptMapper.updateById(s);
        return snapshot(scriptId, "rollback to v" + versionNo);
    }

    /**
     * 删除某脚本的全部版本（脚本删除时由 {@link ScriptService#delete} 触发）。
     */
    public void deleteAllForScript(Long scriptId) {
        versionMapper.delete(new QueryWrapper<ScriptVersion>().eq("script_id", scriptId));
    }

    /** 与 ScriptService.persistScriptBody 保持相同的磁盘布局。 */
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