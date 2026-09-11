package com.bigdata.scriptbox.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.ScriptPreset;
import com.bigdata.scriptbox.entity.ScriptTemplate;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.mapper.ScriptPresetMapper;
import com.bigdata.scriptbox.mapper.ScenarioStepMapper;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.ScriptTemplateService;
import com.bigdata.scriptbox.service.SyntaxCheckService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本管理主控制器。
 *
 * <p>包含：列表 / 详情 / 新建 / 更新 / 删除 / 启停 / 收藏 / 复制 / 语法预检 /
 * 模板创建 / 参数 CRUD / PreCheck 配置。脚本文件本体通过 multipart 上传，
 * 也可以从内置模板派生。
 */
@RestController
@RequestMapping("/api/scripts")
public class ScriptController {

    @Autowired private ScriptService scriptService;
    @Autowired private ScriptTemplateService templateService;
    @Autowired private ExecutionHistoryMapper historyMapper;
    @Autowired private ScriptPresetMapper presetMapper;
    @Autowired private ScenarioStepMapper scenarioStepMapper;
    @Autowired private ScriptBoxProperties props;

    /**
     * 列出全部脚本（按分类、ID 排序）。
     */
    @GetMapping
    public ApiResponse<List<Script>> list() {
        return ApiResponse.ok(scriptService.listAll());
    }

    /**
     * 查询单个脚本及其参数与正文。
     */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) throws IOException {
        Script s = scriptService.getById(id);
        if (s == null) return ApiResponse.error("script not found");
        Map<String, Object> data = new HashMap<>();
        data.put("script", s);
        data.put("params", scriptService.paramsOf(id));
        data.put("body", scriptService.readScriptBody(id));
        return ApiResponse.ok(data);
    }

    /**
     * 通过 multipart 上传新建一个脚本。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Script> create(@RequestParam("name") String name,
                                      @RequestParam(value = "displayName", required = false) String displayName,
                                      @RequestParam(value = "category", required = false) String category,
                                      @RequestParam(value = "description", required = false) String description,
                                      @RequestParam(value = "timeoutSeconds", required = false) Integer timeoutSeconds,
                                      @RequestParam(value = "enabled", required = false) Boolean enabled,
                                      @RequestParam(value = "favorite", required = false) Boolean favorite,
                                      @RequestParam(value = "defaultTenantId", required = false) Long defaultTenantId,
                                      @RequestParam(value = "riskLevel", required = false) String riskLevel,
                                      @RequestParam(value = "allowConcurrent", required = false) Boolean allowConcurrent,
                                      @RequestParam("file") MultipartFile file) throws IOException {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(displayName == null ? name : displayName);
        s.setCategory(category);
        s.setDescription(description);
        s.setTimeoutSeconds(timeoutSeconds == null ? 600 : timeoutSeconds);
        s.setEnabled(enabled == null ? Boolean.TRUE : enabled);
        s.setFavorite(favorite != null && favorite);
        s.setDefaultTenantId(defaultTenantId);
        s.setRiskLevel(riskLevel);
        s.setAllowConcurrent(allowConcurrent);
        Script saved = scriptService.create(s, file);
        log.info("新增脚本，script={}", saved.getName());
        return ApiResponse.ok(saved);
    }

    /**
     * V2: 从内置模板派生一个脚本。
     *
     * <p>模板的正文与可选的 {@code paramsJson} 会被复制到新脚本中；用户输入唯一名。
     * 新脚本仍走 {@code bash -n} 校验，因此模板内容仍受语法约束。
     */
    @PostMapping(value = "/from-template", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Script> createFromTemplate(@RequestBody Map<String, String> body) throws IOException {
        String templateCode = body.get("templateCode");
        String name = body.get("name");
        if (templateCode == null || templateCode.isBlank())
            return ApiResponse.error("templateCode is required");
        if (name == null || name.isBlank())
            return ApiResponse.error("name is required");
        ScriptTemplate t = templateService.findByCode(templateCode);
        if (t == null) return ApiResponse.error("template not found: " + templateCode);
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(name);
        s.setCategory(t.getCategory());
        s.setDescription(t.getDescription());
        s.setTimeoutSeconds(600);
        s.setEnabled(true);
        org.springframework.web.multipart.MultipartFile mf = new com.bigdata.scriptbox.config.InMemoryMultipartFile(
                name + ".sh", name + ".sh", "application/x-sh",
                t.getContent().getBytes(StandardCharsets.UTF_8));
        Script saved = scriptService.create(s, mf);
        // 应用模板自带的参数规格
        if (t.getParamsJson() != null && !t.getParamsJson().isBlank()) {
            try {
                List<ScriptParam> params = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(t.getParamsJson(),
                                new com.fasterxml.jackson.core.type.TypeReference<List<ScriptParam>>() {});
                scriptService.replaceParams(saved.getId(), params);
            } catch (Exception ex) {
                return ApiResponse.error("template param parse failed: " + ex.getMessage());
            }
        }
        log.info("从模板创建脚本，template={}，script={}", templateCode, saved.getName());
        return ApiResponse.ok(saved);
    }

    /**
     * 通过 multipart 更新一个脚本（包含可选的新文件）。
     */
    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Script> update(@PathVariable Long id,
                                      @RequestParam("name") String name,
                                      @RequestParam(value = "displayName", required = false) String displayName,
                                      @RequestParam(value = "category", required = false) String category,
                                      @RequestParam(value = "description", required = false) String description,
                                      @RequestParam(value = "timeoutSeconds", required = false) Integer timeoutSeconds,
                                      @RequestParam(value = "enabled", required = false) Boolean enabled,
                                      @RequestParam(value = "favorite", required = false) Boolean favorite,
                                      @RequestParam(value = "defaultTenantId", required = false) Long defaultTenantId,
                                      @RequestParam(value = "riskLevel", required = false) String riskLevel,
                                      @RequestParam(value = "allowConcurrent", required = false) Boolean allowConcurrent,
                                      @RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
        Script s = new Script();
        s.setId(id);
        s.setName(name);
        s.setDisplayName(displayName == null ? name : displayName);
        s.setCategory(category);
        s.setDescription(description);
        s.setTimeoutSeconds(timeoutSeconds == null ? 600 : timeoutSeconds);
        s.setEnabled(enabled == null ? Boolean.TRUE : enabled);
        s.setFavorite(favorite != null && favorite);
        s.setDefaultTenantId(defaultTenantId);
        s.setRiskLevel(riskLevel);
        s.setAllowConcurrent(allowConcurrent);
        Script updated = scriptService.update(s, file);
        log.info("更新脚本，script={}", updated.getName());
        return ApiResponse.ok(updated);
    }

    /**
     * 仅更新脚本正文（编辑器场景，body 通过 JSON 传入）。
     */
    @PutMapping(value = "/{id}/body", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Script> saveBody(@PathVariable Long id, @RequestBody Map<String, String> body) throws IOException {
        String content = body.get("body");
        if (content == null) return ApiResponse.error("body field is required");
        Script saved = scriptService.saveScriptBody(id, content);
        log.info("脚本保存成功，script={}", saved.getName());
        return ApiResponse.ok(saved);
    }

    /** V2: 编辑时预检 bash 语法，编辑器随打随探。
     *  真正落盘时仍会跑同样的语法检查，这里只是早返回。 */
    @PostMapping(value = "/syntax-check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> syntaxCheck(@RequestBody Map<String, String> body) {
        String content = body.get("body");
        if (content == null) return ApiResponse.error("body field is required");
        SyntaxCheckService.SyntaxResult outcome = scriptService.preflightSyntax(content);
        if (outcome.ok) {
            log.info("Shell 语法校验通过");
        } else {
            log.warn("Shell 语法校验失败，原因={}", String.join(" | ", outcome.errors));
        }
        // SyntaxResult 是 public final fields，把它们打包成 Map 给前端
        Map<String, Object> map = new HashMap<>();
        map.put("ok", outcome.ok);
        map.put("errors", outcome.errors);
        map.put("warnings", outcome.warnings);
        return ApiResponse.ok(map);
    }

    /**
     * 获取脚本正文（纯文本响应）。
     */
    @GetMapping(value = "/{id}/body", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> getBody(@PathVariable Long id) throws IOException {
        String body = scriptService.readScriptBody(id);
        return ResponseEntity.ok(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 删除脚本（同时清理参数、版本、预设，详情见 {@link ScriptService#delete}）。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Script s = scriptService.getById(id);
        if (s != null) {
            scriptService.delete(id);
            log.info("删除脚本，script={}", s.getName());
        } else {
            scriptService.delete(id);
        }
        return ApiResponse.ok();
    }

    /**
     * V2: 删除脚本时关联表行数（用于删除确认对话框展示爆炸半径）。
     * H2 没有外键约束，删除会留下孤儿行；这里让数字可见。
     */
    @GetMapping("/{id}/related-counts")
    public ApiResponse<Map<String, Long>> relatedCounts(@PathVariable Long id) {
        Map<String, Long> out = new HashMap<>();
        out.put("historyCount",
                historyMapper.selectCount(new QueryWrapper<ExecutionHistory>().eq("script_id", id)));
        out.put("presetCount",
                presetMapper.selectCount(new QueryWrapper<ScriptPreset>().eq("script_id", id)));
        // 引用了此脚本的场景步骤数
        out.put("scenarioCount",
                scenarioStepMapper.selectCount(new QueryWrapper<com.bigdata.scriptbox.entity.ScenarioStep>().eq("script_id", id)));
        return ApiResponse.ok(out);
    }

    /**
     * 启用 / 停用脚本。
     */
    @PostMapping("/{id}/enabled")
    public ApiResponse<Script> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        Script s = scriptService.setEnabled(id, enabled);
        if (s == null) return ApiResponse.error("script not found");
        return ApiResponse.ok(s);
    }

    /**
     * 收藏 / 取消收藏脚本。
     */
    @PostMapping("/{id}/favorite")
    public ApiResponse<Script> setFavorite(@PathVariable Long id, @RequestParam boolean favorite) {
        Script s = scriptService.setFavorite(id, favorite);
        if (s == null) return ApiResponse.error("script not found");
        return ApiResponse.ok(s);
    }

    /**
     * 复制一个脚本及其参数（创建为新行，默认 disabled 以避免覆盖原脚本）。
     */
    @PostMapping("/{id}/copy")
    public ApiResponse<Script> copy(@PathVariable Long id,
                                    @RequestParam(value = "suffix", required = false) String suffix) throws IOException {
        Script src = scriptService.getById(id);
        if (src == null) return ApiResponse.error("script not found");
        String body = scriptService.readScriptBody(id);
        Script copy = new Script();
        copy.setName(src.getName() + (suffix != null && !suffix.isBlank() ? suffix : "-copy"));
        copy.setDisplayName((src.getDisplayName() == null ? src.getName() : src.getDisplayName()) + " - Copy");
        copy.setCategory(src.getCategory());
        copy.setDescription(src.getDescription());
        copy.setTimeoutSeconds(src.getTimeoutSeconds());
        copy.setEnabled(Boolean.FALSE); // 复制后默认停用，避免误覆盖
        copy.setFavorite(Boolean.FALSE);
        copy.setDefaultTenantId(src.getDefaultTenantId());
        // 通过内存版 MultipartFile 复用 create 流程
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        org.springframework.web.multipart.MultipartFile mf =
                new com.bigdata.scriptbox.config.InMemoryMultipartFile(
                        "file", copy.getName() + ".sh", "application/x-sh", bytes);
        Script saved = scriptService.create(copy, mf);
        // 复制参数
        List<ScriptParam> params = scriptService.paramsOf(id);
        if (!params.isEmpty()) {
            scriptService.replaceParams(saved.getId(),
                    params.stream().map(p -> {
                        ScriptParam np = new ScriptParam();
                        np.setName(p.getName());
                        np.setLabel(p.getLabel());
                        np.setType(p.getType());
                        np.setDefaultValue(p.getDefaultValue());
                        np.setOptions(p.getOptions());
                        np.setRequired(p.getRequired());
                        np.setSortOrder(p.getSortOrder());
                        np.setPlaceholder(p.getPlaceholder());
                        np.setHelpText(p.getHelpText());
                        return np;
                    }).toList());
        }
        log.info("复制脚本完成，sourceScript={}，newScript={}", src.getName(), saved.getName());
        return ApiResponse.ok(saved);
    }

    // ---- 参数 ----

    /**
     * 列出脚本的全部参数。
     */
    @GetMapping("/{id}/params")
    public ApiResponse<List<ScriptParam>> listParams(@PathVariable Long id) {
        return ApiResponse.ok(scriptService.paramsOf(id));
    }

    /**
     * 整体替换脚本的参数（先删后插）。
     */
    @PutMapping("/{id}/params")
    public ApiResponse<List<ScriptParam>> replaceParams(@PathVariable Long id,
                                                       @RequestBody List<ScriptParam> params) {
        return ApiResponse.ok(scriptService.replaceParams(id, params));
    }

    /**
     * 保存 PreCheck 配置（JSON 字符串持久化到 {@code Script.precheckConfigJson}）。
     * 接受 {@code {"config": {...}}} 或 {@code {"precheckConfigJson": "..."}} 两种形态。
     */
    @PutMapping("/{id}/precheck")
    public ApiResponse<Script> savePrecheck(@PathVariable Long id,
                                            @RequestBody Map<String, Object> body) {
        Script s = scriptService.getById(id);
        if (s == null) return ApiResponse.error("script not found");
        // 兼容两种请求体格式
        Object cfg = body.get("config");
        if (cfg == null) cfg = body.get("precheckConfigJson");
        String json;
        if (cfg instanceof String s2) {
            json = s2;
        } else if (cfg instanceof java.util.Map<?, ?> map) {
            try {
                json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
            } catch (Exception ex) {
                return ApiResponse.error("invalid config: " + ex.getMessage());
            }
        } else {
            json = null;
        }
        s.setPrecheckConfigJson(json);
        s.setUpdateTime(java.time.LocalDateTime.now());
        scriptService.getMapper().updateById(s);
        return ApiResponse.ok(s);
    }

    /**
     * 读取 PreCheck 配置。
     */
    @GetMapping("/{id}/precheck")
    public ApiResponse<Map<String, Object>> getPrecheck(@PathVariable Long id) {
        Script s = scriptService.getById(id);
        if (s == null) return ApiResponse.error("script not found");
        Map<String, Object> out = new HashMap<>();
        out.put("precheckConfigJson", s.getPrecheckConfigJson());
        return ApiResponse.ok(out);
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ScriptController.class);
}