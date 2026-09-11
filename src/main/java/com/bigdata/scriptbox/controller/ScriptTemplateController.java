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

/**
 * 脚本模板只读接口。
 *
 * <p>模板是后端预置的脚本骨架（hive 同步、kafka 抽样、文件校验…），只暴露
 * 启用的列表给前端，供「从模板创建」入口使用。模板本身不可直接执行。
 */
@RestController
@RequestMapping("/api/script-templates")
public class ScriptTemplateController {

    @Autowired private ScriptTemplateService service;

    /**
     * 列出所有启用中的模板。
     *
     * @return 模板列表
     */
    @GetMapping
    public ApiResponse<List<ScriptTemplate>> list() {
        return ApiResponse.ok(service.listEnabled());
    }

    /**
     * 按编码获取单个模板。
     *
     * @param code 模板编码
     * @return 模板详情
     */
    @GetMapping("/{code}")
    public ApiResponse<ScriptTemplate> get(@PathVariable String code) {
        ScriptTemplate t = service.findByCode(code);
        if (t == null) return ApiResponse.error("template not found: " + code);
        return ApiResponse.ok(t);
    }
}