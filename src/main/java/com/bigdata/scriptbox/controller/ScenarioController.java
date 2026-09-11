package com.bigdata.scriptbox.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Scenario;
import com.bigdata.scriptbox.entity.ScenarioStep;
import com.bigdata.scriptbox.mapper.ExecutionHistoryMapper;
import com.bigdata.scriptbox.service.ScenarioService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    @Autowired private ScenarioService scenarioService;
    @Autowired private ExecutionHistoryMapper historyMapper;

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

    /** V2: count of execution_history rows that ran as part of this scenario. */
    @GetMapping("/{id}/related-counts")
    public ApiResponse<Map<String, Long>> relatedCounts(@PathVariable Long id) {
        Map<String, Long> out = new HashMap<>();
        out.put("historyCount",
                historyMapper.selectCount(new QueryWrapper<ExecutionHistory>().eq("scenario_id", id)));
        return ApiResponse.ok(out);
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