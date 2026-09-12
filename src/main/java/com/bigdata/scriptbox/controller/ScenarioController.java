package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.Scenario;
import com.bigdata.scriptbox.entity.ScenarioStep;
import com.bigdata.scriptbox.service.ScenarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 场景编排接口。
 *
 * <p>场景（Scenario）是一组有序步骤的封装，每步绑定一个脚本（可选 Preset），
 * 默认遇错即停，步骤可独立配置 {@code continueOnFailure=true}。
 */
@RestController
@RequestMapping("/api/scenarios")
@RequiredArgsConstructor
public class ScenarioController {

    private final ScenarioService scenarioService;

    /**
     * 列出全部场景。
     */
    @GetMapping
    public ApiResponse<List<Scenario>> list() {
        return ApiResponse.ok(scenarioService.listAll());
    }

    /**
     * 查询单个场景及其步骤。
     *
     * @param id 场景 ID
     */
    @GetMapping("/{id}")
    public ApiResponse<ScenarioDetail> get(@PathVariable Long id) {
        Scenario s = scenarioService.get(id);
        if (s == null) return ApiResponse.error("not found");
        ScenarioDetail d = new ScenarioDetail();
        d.scenario = s;
        d.steps = scenarioService.stepsOf(id);
        return ApiResponse.ok(d);
    }

    /**
     * 创建一个场景（步骤可后续用 {@link #replaceSteps} 设置）。
     *
     * @param s 场景实体
     */
    @PostMapping
    public ApiResponse<Scenario> create(@RequestBody Scenario s) {
        return ApiResponse.ok(scenarioService.create(s));
    }

    /**
     * 更新一个场景的元数据（不含步骤）。
     */
    @PutMapping("/{id}")
    public ApiResponse<Scenario> update(@PathVariable Long id, @RequestBody Scenario s) {
        s.setId(id);
        return ApiResponse.ok(scenarioService.update(s));
    }

    /**
     * 删除一个场景及其所有步骤。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        scenarioService.delete(id);
        return ApiResponse.ok(null);
    }

    /** V2: 关联到本场景的 execution_history 行数（用于删除确认对话框）。 */
    @GetMapping("/{id}/related-counts")
    public ApiResponse<Map<String, Long>> relatedCounts(@PathVariable Long id) {
        return ApiResponse.ok(scenarioService.relatedCounts(id));
    }

    /**
     * 整体替换一个场景的步骤（按请求数组顺序写入并重新编号）。
     */
    @PutMapping("/{id}/steps")
    public ApiResponse<List<ScenarioStep>> replaceSteps(
            @PathVariable Long id, @RequestBody List<ScenarioStep> steps) {
        return ApiResponse.ok(scenarioService.replaceSteps(id, steps));
    }

    /**
     * 按租户执行一个场景。
     *
     * @param id 场景 ID
     * @param tenantId 租户 ID
     */
    @PostMapping("/{id}/run")
    public ApiResponse<ScenarioService.RunResult> run(
            @PathVariable Long id, @RequestParam Long tenantId) {
        try {
            return ApiResponse.ok(scenarioService.run(id, tenantId));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        }
    }

    /** 场景详情响应体：场景元数据 + 步骤列表。 */
    public static class ScenarioDetail {
        public Scenario scenario;
        public List<ScenarioStep> steps;
    }
}