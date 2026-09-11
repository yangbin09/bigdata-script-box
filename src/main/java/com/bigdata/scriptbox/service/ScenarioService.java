package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.dto.ExecutionRequest;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import com.bigdata.scriptbox.entity.Scenario;
import com.bigdata.scriptbox.entity.ScenarioStep;
import com.bigdata.scriptbox.executor.ScriptExecutor;
import com.bigdata.scriptbox.mapper.ScenarioMapper;
import com.bigdata.scriptbox.mapper.ScenarioStepMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple scenario orchestration: ordered sequence of steps where each step
 * runs a script (optionally with a preset). Each step produces one
 * ExecutionHistory row sharing the same scenarioId. Default behaviour: stop
 * on first failure. Steps can opt in to continueOnFailure.
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    @Autowired private ScenarioMapper scenarioMapper;
    @Autowired private ScenarioStepMapper stepMapper;
    @Autowired private ScriptExecutor executor;

    public Scenario create(Scenario s) {
        if (s.getId() == null && (s.getEnabled() == null)) s.setEnabled(true);
        s.setCreateTime(LocalDateTime.now());
        s.setUpdateTime(LocalDateTime.now());
        scenarioMapper.insert(s);
        return s;
    }

    public Scenario update(Scenario s) {
        s.setUpdateTime(LocalDateTime.now());
        scenarioMapper.updateById(s);
        return s;
    }

    public Scenario get(Long id) {
        return scenarioMapper.selectById(id);
    }

    public List<Scenario> listAll() {
        return scenarioMapper.selectList(null);
    }

    public void delete(Long id) {
        stepMapper.deleteByScenarioId(id);
        scenarioMapper.deleteById(id);
    }

    /** Replace the steps of a scenario in one shot. Steps are assigned sequential stepNo. */
    public List<ScenarioStep> replaceSteps(Long scenarioId, List<ScenarioStep> steps) {
        stepMapper.deleteByScenarioId(scenarioId);
        List<ScenarioStep> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            ScenarioStep s = steps.get(i);
            s.setId(null);
            s.setScenarioId(scenarioId);
            s.setStepNo(i);
            s.setCreateTime(LocalDateTime.now());
            stepMapper.insert(s);
            out.add(s);
        }
        return out;
    }

    public List<ScenarioStep> stepsOf(Long scenarioId) {
        return stepMapper.selectByScenarioIdOrderByStep(scenarioId);
    }

    /** Run all steps in order. */
    public RunResult run(Long scenarioId, Long tenantId) {
        Scenario sc = scenarioMapper.selectById(scenarioId);
        if (sc == null) throw new IllegalArgumentException("scenario not found: " + scenarioId);
        if (sc.getEnabled() != null && !sc.getEnabled())
            throw new IllegalArgumentException("scenario disabled: " + sc.getName());
        List<ScenarioStep> steps = stepMapper.selectByScenarioIdOrderByStep(scenarioId);
        if (steps.isEmpty()) throw new IllegalArgumentException("scenario has no steps");

        RunResult result = new RunResult();
        result.scenarioId = scenarioId;
        result.total = steps.size();
        result.entries = new ArrayList<>();
        for (ScenarioStep s : steps) {
            RunEntry entry = new RunEntry();
            entry.stepNo = s.getStepNo();
            entry.scriptId = s.getScriptId();
            entry.presetId = s.getPresetId();
            ExecutionRequest req = new ExecutionRequest();
            req.setScriptId(s.getScriptId());
            req.setTenantId(tenantId);
            req.setPresetId(s.getPresetId());
            req.setScenarioId(scenarioId);
            req.setScenarioStepNo(s.getStepNo());
            try {
                ExecutionHistory h = executor.execute(req);
                entry.historyId = h.getId();
                entry.success = Boolean.TRUE.equals(h.getSuccess());
                entry.status = h.getStatus();
                result.historyIds.add(h.getId());
                if (entry.success) result.succeeded++;
                else result.failed++;
                result.entries.add(entry);
                if (!entry.success && !Boolean.TRUE.equals(s.getContinueOnFailure())) {
                    result.aborted = true;
                    result.abortedAtStep = s.getStepNo();
                    break;
                }
            } catch (Exception ex) {
                log.warn("scenario {} step {} failed: {}", scenarioId, s.getStepNo(), ex.getMessage());
                entry.success = false;
                entry.status = "ERROR";
                entry.error = ex.getMessage();
                result.failed++;
                result.entries.add(entry);
                if (!Boolean.TRUE.equals(s.getContinueOnFailure())) {
                    result.aborted = true;
                    result.abortedAtStep = s.getStepNo();
                    break;
                }
            }
        }
        return result;
    }

    public static class RunResult {
        public Long scenarioId;
        public int total;
        public int succeeded;
        public int failed;
        public boolean aborted;
        public Integer abortedAtStep;
        public List<Long> historyIds = new ArrayList<>();
        public List<RunEntry> entries;
    }

    public static class RunEntry {
        public Integer stepNo;
        public Long scriptId;
        public Long presetId;
        public Long historyId;
        public Boolean success;
        public String status;
        public String error;
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stepNo", stepNo);
            m.put("scriptId", scriptId);
            m.put("presetId", presetId);
            m.put("historyId", historyId);
            m.put("success", success);
            m.put("status", status);
            if (error != null) m.put("error", error);
            return m;
        }
    }
}