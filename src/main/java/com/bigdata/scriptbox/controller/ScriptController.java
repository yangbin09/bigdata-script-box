package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.bigdata.scriptbox.service.ScriptService;
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
                                      @RequestParam("file") MultipartFile file) throws IOException {
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(displayName == null ? name : displayName);
        s.setCategory(category);
        s.setDescription(description);
        s.setTimeoutSeconds(timeoutSeconds == null ? 600 : timeoutSeconds);
        s.setEnabled(enabled == null ? Boolean.TRUE : enabled);
        return ApiResponse.ok(scriptService.create(s, file));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Script> update(@PathVariable Long id,
                                      @RequestParam("name") String name,
                                      @RequestParam(value = "displayName", required = false) String displayName,
                                      @RequestParam(value = "category", required = false) String category,
                                      @RequestParam(value = "description", required = false) String description,
                                      @RequestParam(value = "timeoutSeconds", required = false) Integer timeoutSeconds,
                                      @RequestParam(value = "enabled", required = false) Boolean enabled,
                                      @RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
        Script s = new Script();
        s.setId(id);
        s.setName(name);
        s.setDisplayName(displayName == null ? name : displayName);
        s.setCategory(category);
        s.setDescription(description);
        s.setTimeoutSeconds(timeoutSeconds == null ? 600 : timeoutSeconds);
        s.setEnabled(enabled == null ? Boolean.TRUE : enabled);
        return ApiResponse.ok(scriptService.update(s, file));
    }

    @PutMapping(value = "/{id}/body", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Script> saveBody(@PathVariable Long id, @RequestBody Map<String, String> body) throws IOException {
        String content = body.get("body");
        if (content == null) return ApiResponse.error("body field is required");
        return ApiResponse.ok(scriptService.saveScriptBody(id, content));
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
}