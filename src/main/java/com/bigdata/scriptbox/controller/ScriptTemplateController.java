package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ScriptTemplate;
import com.bigdata.scriptbox.service.ScriptTemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/script-templates")
public class ScriptTemplateController {

    @Autowired private ScriptTemplateService service;

    @GetMapping
    public ApiResponse<List<ScriptTemplate>> list() {
        return ApiResponse.ok(service.listEnabled());
    }

    @GetMapping("/{code}")
    public ApiResponse<ScriptTemplate> get(@PathVariable String code) {
        ScriptTemplate t = service.findByCode(code);
        if (t == null) return ApiResponse.error("template not found: " + code);
        return ApiResponse.ok(t);
    }
}