package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.Scenario;
import com.bigdata.scriptbox.entity.ScenarioStep;
import com.bigdata.scriptbox.service.ScenarioService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    @Autowired private ScenarioService scenarioService;

    @GetMapping
    public ApiResponse<List<Scenario>> list() {
        return ApiResponse.ok(scenarioService.listAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<ScenarioDetail> get(@PathVariable Long id) {
        Scenario s = scenarioService.get(id);
        if (s == null) return ApiResponse.error("not found");
        ScenarioDetail d = new ScenarioDetail();
        d.scenario = s;
        d.steps = scenarioService.stepsOf(id);
        return ApiResponse.ok(d);
    }

    @PostMapping
    public ApiResponse<Scenario> create(@RequestBody Scenario s) {
        return ApiResponse.ok(scenarioService.create(s));
    }

    @PutMapping("/{id}")
    public ApiResponse<Scenario> update(@PathVariable Long id, @RequestBody Scenario s) {
        s.setId(id);
        return ApiResponse.ok(scenarioService.update(s));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        scenarioService.delete(id);
        return ApiResponse.ok(null);
    }

    @PutMapping("/{id}/steps")
    public ApiResponse<List<ScenarioStep>> replaceSteps(
            @PathVariable Long id, @RequestBody List<ScenarioStep> steps) {
        return ApiResponse.ok(scenarioService.replaceSteps(id, steps));
    }

    @PostMapping("/{id}/run")
    public ApiResponse<ScenarioService.RunResult> run(
            @PathVariable Long id, @RequestParam Long tenantId) {
        try {
            return ApiResponse.ok(scenarioService.run(id, tenantId));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        }
    }

    public static class ScenarioDetail {
        public Scenario scenario;
        public List<ScenarioStep> steps;
    }
}