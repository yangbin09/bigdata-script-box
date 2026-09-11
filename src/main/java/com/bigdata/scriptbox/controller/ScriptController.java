package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.entity.ScriptTemplate;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.ScriptTemplateService;
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

@RestController
@RequestMapping("/api/scripts")
public class ScriptController {

    @Autowired private ScriptService scriptService;
    @Autowired private ScriptTemplateService templateService;

    @GetMapping
    public ApiResponse<List<Script>> list() {
        return ApiResponse.ok(scriptService.listAll());
    }

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
        return ApiResponse.ok(scriptService.create(s, file));
    }

    /**
     * V2: create a Script from a built-in template. The template's content
     * and (optional) paramsJson are copied into a new Script row; the user
     * picks a unique name. The new Script is then editable through the
     * regular edit flow — template content still goes through bash -n.
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
        // Apply the template's parameter spec, if any.
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
        return ApiResponse.ok(saved);
    }

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
        return ApiResponse.ok(scriptService.update(s, file));
    }

    @PutMapping(value = "/{id}/body", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Script> saveBody(@PathVariable Long id, @RequestBody Map<String, String> body) throws IOException {
        String content = body.get("body");
        if (content == null) return ApiResponse.error("body field is required");
        return ApiResponse.ok(scriptService.saveScriptBody(id, content));
    }

    /** V2: preflight syntax check. The editor can call this as the user
     *  types to surface problems without committing. The same bash -n
     *  gate fires on the actual save — this endpoint is just a probe. */
    @PostMapping(value = "/syntax-check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> syntaxCheck(@RequestBody Map<String, String> body) {
        String content = body.get("body");
        if (content == null) return ApiResponse.error("body field is required");
        return ApiResponse.ok(scriptService.preflightSyntax(content).toMap());
    }

    @GetMapping(value = "/{id}/body", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<byte[]> getBody(@PathVariable Long id) throws IOException {
        String body = scriptService.readScriptBody(id);
        return ResponseEntity.ok(body.getBytes(StandardCharsets.UTF_8));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        scriptService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<Script> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        Script s = scriptService.setEnabled(id, enabled);
        if (s == null) return ApiResponse.error("script not found");
        return ApiResponse.ok(s);
    }

    @PostMapping("/{id}/favorite")
    public ApiResponse<Script> setFavorite(@PathVariable Long id, @RequestParam boolean favorite) {
        Script s = scriptService.setFavorite(id, favorite);
        if (s == null) return ApiResponse.error("script not found");
        return ApiResponse.ok(s);
    }

    /** Duplicate an existing script (and its params) under a new name. */
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
        copy.setEnabled(Boolean.FALSE); // disabled until edited to avoid clobbering
        copy.setFavorite(Boolean.FALSE);
        copy.setDefaultTenantId(src.getDefaultTenantId());
        // Reuse the create pipeline via an in-memory upload
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        org.springframework.web.multipart.MultipartFile mf =
                new com.bigdata.scriptbox.config.InMemoryMultipartFile(
                        "file", copy.getName() + ".sh", "application/x-sh", bytes);
        Script saved = scriptService.create(copy, mf);
        // Duplicate params
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
        return ApiResponse.ok(saved);
    }

    // ---- params ----

    @GetMapping("/{id}/params")
    public ApiResponse<List<ScriptParam>> listParams(@PathVariable Long id) {
        return ApiResponse.ok(scriptService.paramsOf(id));
    }

    @PutMapping("/{id}/params")
    public ApiResponse<List<ScriptParam>> replaceParams(@PathVariable Long id,
                                                       @RequestBody List<ScriptParam> params) {
        return ApiResponse.ok(scriptService.replaceParams(id, params));
    }

    /** Save pre-execution check config as a JSON string on the Script row. */
    @PutMapping("/{id}/precheck")
    public ApiResponse<Script> savePrecheck(@PathVariable Long id,
                                            @RequestBody Map<String, Object> body) {
        Script s = scriptService.getById(id);
        if (s == null) return ApiResponse.error("script not found");
        // Accept either {"config": {...}} or {"precheckConfigJson": "..."}.
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

    @GetMapping("/{id}/precheck")
    public ApiResponse<Map<String, Object>> getPrecheck(@PathVariable Long id) {
        Script s = scriptService.getById(id);
        if (s == null) return ApiResponse.error("script not found");
        Map<String, Object> out = new HashMap<>();
        out.put("precheckConfigJson", s.getPrecheckConfigJson());
        return ApiResponse.ok(out);
    }
}