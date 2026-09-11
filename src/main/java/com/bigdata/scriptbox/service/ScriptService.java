package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.mapper.ScriptMapper;
import com.bigdata.scriptbox.mapper.ScriptParamMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class ScriptService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "text", "number", "select", "boolean", "date", "textarea", "file");

    @Autowired
    private ScriptMapper scriptMapper;

    @Autowired
    private ScriptParamMapper scriptParamMapper;

    @Autowired
    private ScriptBoxProperties props;

    @Autowired
    private ScriptVersionService versionService;

    @Autowired
    private PresetService presetService;

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(Paths.get(props.getScriptsDir()));
    }

    public List<Script> listAll() {
        return scriptMapper.selectList(new QueryWrapper<Script>().orderByAsc("category", "id"));
    }

    public Script getById(Long id) {
        return scriptMapper.selectById(id);
    }

    public List<ScriptParam> paramsOf(Long scriptId) {
        return scriptParamMapper.selectByScriptId(scriptId);
    }

    @Transactional
    public Script create(Script script, MultipartFile file) throws IOException {
        if (script.getTimeoutSeconds() == null) script.setTimeoutSeconds(600);
        if (script.getEnabled() == null) script.setEnabled(Boolean.TRUE);
        if (script.getFavorite() == null) script.setFavorite(Boolean.FALSE);

        String body = readScriptFile(file);

        LocalDateTime now = LocalDateTime.now();
        script.setCreateTime(now);
        script.setUpdateTime(now);
        // Insert first so the auto-increment id is known, then write the file under that id.
        script.setScriptPath("");
        scriptMapper.insert(script);

        Path scriptFile = persistScriptBody(script.getId(), body);
        script.setScriptPath(scriptFile.toAbsolutePath().toString());
        scriptMapper.updateById(script);
        // Snapshot v1 (initial creation)
        versionService.snapshot(script.getId(), "initial");
        return script;
    }

    @Transactional
    public Script update(Script script, MultipartFile file) throws IOException {
        Script db = scriptMapper.selectById(script.getId());
        if (db == null) throw new IllegalArgumentException("script not found: " + script.getId());

        if (file != null && !file.isEmpty()) {
            String body = readScriptFile(file);
            Path scriptFile = persistScriptBody(db.getId(), body);
            script.setScriptPath(scriptFile.toAbsolutePath().toString());
        } else {
            script.setScriptPath(db.getScriptPath());
        }
        if (script.getEnabled() == null) script.setEnabled(db.getEnabled());
        if (script.getFavorite() == null) script.setFavorite(db.getFavorite());
        script.setUpdateTime(LocalDateTime.now());
        scriptMapper.updateById(script);
        return scriptMapper.selectById(script.getId());
    }

    public Script saveScriptBody(Long id, String body) throws IOException {
        Script db = scriptMapper.selectById(id);
        if (db == null) throw new IllegalArgumentException("script not found: " + id);
        Path scriptFile = persistScriptBody(id, body);
        db.setScriptPath(scriptFile.toAbsolutePath().toString());
        db.setUpdateTime(LocalDateTime.now());
        scriptMapper.updateById(db);
        versionService.snapshot(id, "body edit");
        return db;
    }

    public String readScriptBody(Long id) throws IOException {
        Script db = scriptMapper.selectById(id);
        if (db == null) throw new IllegalArgumentException("script not found: " + id);
        if (db.getScriptPath() == null) return "";
        Path p = Paths.get(db.getScriptPath());
        if (!Files.exists(p)) return "";
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    public void delete(Long id) {
        Script db = scriptMapper.selectById(id);
        if (db == null) return;
        scriptMapper.deleteById(id);
        scriptParamMapper.delete(new QueryWrapper<ScriptParam>().eq("script_id", id));
        try { versionService.deleteAllForScript(id); } catch (Exception ignored) {}
        try { presetService.deleteAllForScript(id); } catch (Exception ignored) {}
        if (db.getScriptPath() != null) {
            try {
                Path p = Paths.get(db.getScriptPath());
                Files.deleteIfExists(p);
                Path parent = p.getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    try (var stream = Files.list(parent)) {
                        if (stream.findAny().isEmpty()) Files.deleteIfExists(parent);
                    }
                }
            } catch (IOException ignored) { }
        }
    }

    /**
     * Wipe a script along with its presets, versions and versions-history. Called
     * by delete(Long); split out so tests can call it directly.
     */
    public void deleteCascade(Long id) {
        delete(id);
        // Presets / Versions are cleaned by their respective services via wrapper calls
        // from the controller; here we only guarantee param + file cleanup.
    }

    public Script setEnabled(Long id, boolean enabled) {
        Script db = scriptMapper.selectById(id);
        if (db == null) return null;
        db.setEnabled(enabled);
        db.setUpdateTime(LocalDateTime.now());
        scriptMapper.updateById(db);
        return db;
    }

    public Script setFavorite(Long id, boolean favorite) {
        Script db = scriptMapper.selectById(id);
        if (db == null) return null;
        db.setFavorite(favorite);
        db.setUpdateTime(LocalDateTime.now());
        scriptMapper.updateById(db);
        return db;
    }

    /** Exposed for ScriptController.savePrecheck to avoid a separate mapper injection. */
    public ScriptMapper getMapper() { return scriptMapper; }

    // ---- params ----

    @Transactional
    public List<ScriptParam> replaceParams(Long scriptId, List<ScriptParam> params) {
        scriptParamMapper.delete(new QueryWrapper<ScriptParam>().eq("script_id", scriptId));
        int order = 0;
        for (ScriptParam p : params) {
            if (p.getName() == null || p.getName().isBlank())
                throw new IllegalArgumentException("param name is required");
            if (p.getType() == null || !ALLOWED_TYPES.contains(p.getType().toLowerCase()))
                throw new IllegalArgumentException("invalid param type: " + p.getType());
            p.setScriptId(scriptId);
            if (p.getSortOrder() == null) p.setSortOrder(order++);
            if (p.getRequired() == null) p.setRequired(Boolean.FALSE);
            scriptParamMapper.insert(p);
        }
        return paramsOf(scriptId);
    }

    // ---- internal ----

    private String readScriptFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("script file is required");
        if (file.getSize() > props.getMaxScriptBytes())
            throw new IllegalArgumentException("script exceeds size limit");
        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase().endsWith(".sh"))
            throw new IllegalArgumentException("only .sh files are allowed");
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    private Path persistScriptBody(Long scriptId, String body) throws IOException {
        Path scriptsRoot = Paths.get(props.getScriptsDir()).toAbsolutePath();
        Path dir = scriptsRoot.resolve(String.valueOf(scriptId));
        Files.createDirectories(dir);
        Path file = dir.resolve("script.sh");
        if (!file.toAbsolutePath().startsWith(scriptsRoot)) {
            throw new IllegalStateException("script path escapes scripts dir");
        }
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }
}