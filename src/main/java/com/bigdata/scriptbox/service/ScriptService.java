package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.mapper.ScriptMapper;
import com.bigdata.scriptbox.mapper.ScriptParamMapper;
import com.bigdata.scriptbox.model.RiskLevel;
import com.bigdata.scriptbox.model.VisibleWhen;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * 脚本服务。
 *
 * <p>负责脚本元数据 + 文件本体的 CRUD。所有写操作（create / update / saveBody）
 * 都会先跑 {@code bash -n} 语法检查，阻止解析失败的脚本被持久化。
 *
 * <p>文件本体落在 {@link StoragePathService#scriptsRoot()} 下的受控目录，
 * 每个脚本一个子目录，正文文件名固定为 {@code script.sh}。版本历史与
 * Preset 由各自的 Service 维护。
 */
@Service
public class ScriptService {

    private static final Logger log = LoggerFactory.getLogger(ScriptService.class);

    /** 允许的参数类型集合（前端 select 用）。 */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "text", "number", "select", "boolean", "date", "textarea", "file");

    private final ScriptMapper scriptMapper;
    private final ScriptParamMapper scriptParamMapper;
    private final ScriptBoxProperties props;
    private final ScriptVersionService versionService;
    private final PresetService presetService;
    private final SyntaxCheckService syntaxCheckService;
    private final StoragePathService storagePathService;

    /** 构造器注入：依赖显式化，字段 final 不可变，便于单元测试。 */
    public ScriptService(ScriptMapper scriptMapper,
                         ScriptParamMapper scriptParamMapper,
                         ScriptBoxProperties props,
                         ScriptVersionService versionService,
                         PresetService presetService,
                         SyntaxCheckService syntaxCheckService,
                         StoragePathService storagePathService) {
        this.scriptMapper = scriptMapper;
        this.scriptParamMapper = scriptParamMapper;
        this.props = props;
        this.versionService = versionService;
        this.presetService = presetService;
        this.syntaxCheckService = syntaxCheckService;
        this.storagePathService = storagePathService;
    }

    /** 启动时确保脚本根目录存在。 */
    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(storagePathService.scriptsRoot());
    }

    /**
     * 列出全部脚本，按分类、ID 排序。
     */
    public List<Script> listAll() {
        return scriptMapper.selectList(new QueryWrapper<Script>().orderByAsc("category", "id"));
    }

    /**
     * 按主键查询脚本元数据。
     *
     * @param id 脚本 ID
     * @return 实体（可能为 null）
     */
    public Script getById(Long id) {
        return scriptMapper.selectById(id);
    }

    /**
     * 查询脚本的全部参数定义。
     *
     * @param scriptId 脚本 ID
     * @return 参数列表
     */
    public List<ScriptParam> paramsOf(Long scriptId) {
        return scriptParamMapper.selectByScriptId(scriptId);
    }

    /**
     * 新建一个脚本（带文件）。流程：补默认值 → 风险等级校验 → bash -n 语法检查
     * → 写入 DB（先取自增 ID） → 落盘脚本正文 → 写一行 v1 版本。
     *
     * @param script 实体（不含 ID）
     * @param file 用户上传的 .sh 文件
     * @return 持久化后的脚本实体（含 ID 与绝对路径）
     */
    @Transactional
    public Script create(Script script, MultipartFile file) throws IOException {
        if (script.getTimeoutSeconds() == null) script.setTimeoutSeconds(600);
        if (script.getEnabled() == null) script.setEnabled(Boolean.TRUE);
        if (script.getFavorite() == null) script.setFavorite(Boolean.FALSE);
        // V2: 风险等级缺省 READ_ONLY；显式给的值必须通过白名单校验
        if (script.getRiskLevel() == null || script.getRiskLevel().isBlank()) {
            script.setRiskLevel(RiskLevel.READ_ONLY);
        } else {
            // 严格校验：拒绝 "BOGUS" 之类非法值
            RiskLevel.requireValid(script.getRiskLevel());
            script.setRiskLevel(script.getRiskLevel().trim().toUpperCase());
        }
        if (script.getAllowConcurrent() == null) script.setAllowConcurrent(Boolean.FALSE);

        String body = readScriptFile(file);

        // V2: 强制 bash -n 语法检查；所有错误信息汇聚到异常 message 里给前端展示
        SyntaxCheckService.SyntaxResult sr = syntaxCheckService.check(body);
        if (!sr.ok) {
            log.warn("脚本创建语法校验失败，原因={}", String.join(" | ", sr.errors));
            throw new IllegalArgumentException(
                    "syntax check failed: " + String.join(" | ", sr.errors));
        }

        LocalDateTime now = LocalDateTime.now();
        script.setCreateTime(now);
        script.setUpdateTime(now);
        // 先 insert 取自增 ID，再按 ID 写文件
        script.setScriptPath("");
        scriptMapper.insert(script);

        Path scriptFile = persistScriptBody(script.getId(), body);
        script.setScriptPath(scriptFile.toAbsolutePath().toString());
        scriptMapper.updateById(script);
        // 落一份 v1 版本
        versionService.snapshot(script.getId(), "initial");
        log.info("脚本创建成功，scriptId={}，script={}", script.getId(), script.getName());
        return script;
    }

    /**
     * 更新一个脚本的元数据，可选附带新文件。带新文件时同样先跑 bash -n。
     *
     * @param script 实体（必含 ID）
     * @param file 可选的新 .sh 文件
     * @return 更新后的实体
     */
    @Transactional
    public Script update(Script script, MultipartFile file) throws IOException {
        Script db = scriptMapper.selectById(script.getId());
        if (db == null) throw new IllegalArgumentException("script not found: " + script.getId());

        if (file != null && !file.isEmpty()) {
            String body = readScriptFile(file);
            // V2: 上传新版 .sh 也走 bash -n，不能让语法错的文件悄悄落盘
            SyntaxCheckService.SyntaxResult sr = syntaxCheckService.check(body);
            if (!sr.ok) {
                log.warn("脚本更新语法校验失败，scriptId={}，原因={}", script.getId(), String.join(" | ", sr.errors));
                throw new IllegalArgumentException(
                        "syntax check failed: " + String.join(" | ", sr.errors));
            }
            Path scriptFile = persistScriptBody(db.getId(), body);
            script.setScriptPath(scriptFile.toAbsolutePath().toString());
        } else {
            script.setScriptPath(db.getScriptPath());
        }
        if (script.getEnabled() == null) script.setEnabled(db.getEnabled());
        if (script.getFavorite() == null) script.setFavorite(db.getFavorite());
        // V2: 更新时也校验风险等级；null 表示"不动"
        if (script.getRiskLevel() != null) {
            RiskLevel.requireValid(script.getRiskLevel());
            script.setRiskLevel(script.getRiskLevel().trim().toUpperCase());
        } else {
            script.setRiskLevel(db.getRiskLevel());
        }
        if (script.getAllowConcurrent() == null) script.setAllowConcurrent(db.getAllowConcurrent());
        script.setUpdateTime(LocalDateTime.now());
        scriptMapper.updateById(script);
        return scriptMapper.selectById(script.getId());
    }

    /**
     * 仅保存脚本正文（编辑器场景）。同样走 bash -n，并把当前正文快照成新版本。
     *
     * @param id 脚本 ID
     * @param body 新正文
     * @return 更新后的实体
     */
    public Script saveScriptBody(Long id, String body) throws IOException {
        Script db = scriptMapper.selectById(id);
        if (db == null) throw new IllegalArgumentException("script not found: " + id);
        // V2: 编辑器保存也强制 bash -n，阻止一个 typo 让脚本不可执行直到人肉发现
        SyntaxCheckService.SyntaxResult sr = syntaxCheckService.check(body);
        if (!sr.ok) {
            throw new IllegalArgumentException(
                    "syntax check failed: " + String.join(" | ", sr.errors));
        }
        Path scriptFile = persistScriptBody(id, body);
        db.setScriptPath(scriptFile.toAbsolutePath().toString());
        db.setUpdateTime(LocalDateTime.now());
        scriptMapper.updateById(db);
        versionService.snapshot(id, "body edit");
        return db;
    }

    /**
     * 读取脚本正文。
     *
     * @param id 脚本 ID
     * @return 文件内容（文件不存在时返回空串）
     */
    public String readScriptBody(Long id) throws IOException {
        Script db = scriptMapper.selectById(id);
        if (db == null) throw new IllegalArgumentException("script not found: " + id);
        if (db.getScriptPath() == null) return "";
        Path p = Paths.get(db.getScriptPath());
        if (!Files.exists(p)) return "";
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * 删除脚本及其参数、版本、预设和正文文件。
     *
     * @param id 脚本 ID
     */
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
                // 目录空了就把目录删掉，避免遗留无数空目录
                Path parent = p.getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    try (var stream = Files.list(parent)) {
                        if (stream.findAny().isEmpty()) Files.deleteIfExists(parent);
                    }
                }
            } catch (IOException ignored) { }
        }
        log.info("脚本已删除，scriptId={}", id);
    }

    /**
     * 兼容性入口：保留旧 API；实际工作委托给 {@link #delete(Long)}。
     */
    public void deleteCascade(Long id) {
        delete(id);
    }

    /**
     * 启用 / 停用一个脚本。
     *
     * @param id 脚本 ID
     * @param enabled true 启用 / false 停用
     * @return 更新后的实体
     */
    public Script setEnabled(Long id, boolean enabled) {
        Script db = scriptMapper.selectById(id);
        if (db == null) return null;
        db.setEnabled(enabled);
        db.setUpdateTime(LocalDateTime.now());
        scriptMapper.updateById(db);
        return db;
    }

    /**
     * 收藏 / 取消收藏一个脚本。
     */
    public Script setFavorite(Long id, boolean favorite) {
        Script db = scriptMapper.selectById(id);
        if (db == null) return null;
        db.setFavorite(favorite);
        db.setUpdateTime(LocalDateTime.now());
        scriptMapper.updateById(db);
        return db;
    }

    /** 暴露给 ScriptController.savePrecheck 用，避免 controller 重复注入 mapper。 */
    public ScriptMapper getMapper() { return scriptMapper; }

    /**
     * V2: 编辑器随打随探的 bash -n 入口。不会持久化任何东西；返回结构化
     * SyntaxResult 让前端能渲染错误 / 警告。本方法自身不抛语法错误。
     */
    public SyntaxCheckService.SyntaxResult preflightSyntax(String body) {
        return syntaxCheckService.check(body);
    }

    // ---- params ----

    /**
     * 整体替换一个脚本的参数（先删后插）。同时校验每条 visibleWhenJson 的形状与
     * 引用合法性；不合法时立刻抛错，数据库不会有半插入状态。
     *
     * @param scriptId 脚本 ID
     * @param params 新的参数列表
     * @return 替换后的参数列表
     */
    @Transactional
    public List<ScriptParam> replaceParams(Long scriptId, List<ScriptParam> params) {
        scriptParamMapper.delete(new QueryWrapper<ScriptParam>().eq("script_id", scriptId));
        // 预收集声明名，用于校验 visibleWhen 引用的合法性
        java.util.Set<String> declaredNames = new java.util.HashSet<>();
        for (ScriptParam p : params) {
            if (p.getName() != null && !p.getName().isBlank()) declaredNames.add(p.getName().trim());
        }
        int order = 0;
        for (ScriptParam p : params) {
            if (p.getName() == null || p.getName().isBlank())
                throw new IllegalArgumentException("param name is required");
            if (p.getType() == null || !ALLOWED_TYPES.contains(p.getType().toLowerCase()))
                throw new IllegalArgumentException("invalid param type: " + p.getType());
            p.setScriptId(scriptId);
            if (p.getSortOrder() == null) p.setSortOrder(order++);
            if (p.getRequired() == null) p.setRequired(Boolean.FALSE);
            // V2: visibleWhen 引用未声明的 param 时，立刻报错而不是留到 UI 阶段
            if (p.getVisibleWhenJson() != null && !p.getVisibleWhenJson().isBlank()) {
                VisibleWhen rule = VisibleWhen.parse(p.getVisibleWhenJson());
                if (rule == null || rule.getParam() == null) {
                    throw new IllegalArgumentException(
                        "invalid visibleWhenJson for param '" + p.getName() + "': " + p.getVisibleWhenJson());
                }
                if (!declaredNames.contains(rule.getParam())) {
                    throw new IllegalArgumentException(
                        "visibleWhen for param '" + p.getName() + "' references unknown param '"
                        + rule.getParam() + "'");
                }
            }
            scriptParamMapper.insert(p);
        }
        return paramsOf(scriptId);
    }

    // ---- internal ----

    /**
     * V2: 按 visibleWhen 规则过滤参数。被隐藏的参数永远不传给执行器，即便
     * 前端提交了值（执行器也会再丢弃一次，作为防御性兜底）。
     */
    public List<ScriptParam> filterVisible(List<ScriptParam> declared, java.util.Map<String, String> resolvedValues) {
        java.util.List<ScriptParam> out = new java.util.ArrayList<>();
        for (ScriptParam p : declared) {
            VisibleWhen rule = VisibleWhen.parse(p.getVisibleWhenJson());
            if (rule == null || rule.matches(resolvedValues)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 读取上传文件内容，做基本校验（必填、扩展名、字节上限）。
     *
     * @param file multipart 文件
     * @return 正文
     */
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

    /**
     * 把脚本正文写到 {@code <scriptsRoot>/<id>/script.sh}。落盘前会校验路径
     * 没逃出受控根目录，阻止恶意 / 拼错路径覆盖系统文件。
     *
     * @param scriptId 脚本 ID
     * @param body 正文
     * @return 落盘后的绝对路径
     */
    private Path persistScriptBody(Long scriptId, String body) throws IOException {
        Path file = storagePathService.scriptFilePath(scriptId);
        // 路径安全：阻断通过 scriptPath 字段越界写文件
        storagePathService.assertInside(file, storagePathService.scriptsRoot(), "script file");
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }
}