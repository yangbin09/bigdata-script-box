package com.bigdata.scriptbox.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
 * 简单场景编排：按顺序执行一组步骤，每一步调用一个脚本（可绑定预设）。每一步
 * 产生一条 {@link ExecutionHistory}，共享同一个 scenarioId。
 *
 * <p>默认行为：首次失败即终止（fail-fast）。步骤可在 {@code ScenarioStep.continueOnFailure}
 * 上选择"失败继续"。
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    @Autowired private ScenarioMapper scenarioMapper;
    @Autowired private ScenarioStepMapper stepMapper;
    @Autowired private ScriptExecutor executor;

    /**
     * 创建场景。{@code id} 与 {@code enabled} 同时为空时默认启用。
     */
    public Scenario create(Scenario s) {
        if (s.getId() == null && (s.getEnabled() == null)) s.setEnabled(true);
        s.setCreateTime(LocalDateTime.now());
        s.setUpdateTime(LocalDateTime.now());
        scenarioMapper.insert(s);
        log.info("scenario: 新增 scenarioId={} name={}", s.getId(), s.getName());
        return s;
    }

    /** 更新场景（仅修改 updateTime + 业务字段，id 不变）。 */
    public Scenario update(Scenario s) {
        s.setUpdateTime(LocalDateTime.now());
        scenarioMapper.updateById(s);
        log.info("scenario: 更新 scenarioId={} name={}", s.getId(), s.getName());
        return s;
    }

    /** 按主键查场景。 */
    public Scenario get(Long id) {
        return scenarioMapper.selectById(id);
    }

    /** 列出所有场景。 */
    public List<Scenario> listAll() {
        return scenarioMapper.selectList(null);
    }

    /** 删除场景（先删步骤再删场景本体）。 */
    public void delete(Long id) {
        stepMapper.deleteByScenarioId(id);
        scenarioMapper.deleteById(id);
        log.info("scenario: 删除 scenarioId={}", id);
    }

    /**
     * 一次性替换场景的步骤集合。传入步骤按顺序被分配递增的 stepNo。
     */
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

    /** 列出某场景下的所有步骤（按 stepNo 升序）。 */
    public List<ScenarioStep> stepsOf(Long scenarioId) {
        return stepMapper.selectByScenarioIdOrderByStep(scenarioId);
    }

    /**
     * 按顺序执行场景全部步骤。任一步骤抛异常 / 失败时，若该步骤未勾选
     * {@code continueOnFailure}，则中止后续步骤；否则继续。返回值 {@link RunResult}
     * 包含每一步的执行细节与中止原因。
     */
    public RunResult run(Long scenarioId, Long tenantId) {
        Scenario sc = scenarioMapper.selectById(scenarioId);
        if (sc == null) throw new IllegalArgumentException("scenario not found: " + scenarioId);
        if (sc.getEnabled() != null && !sc.getEnabled())
            throw new IllegalArgumentException("scenario disabled: " + sc.getName());
        List<ScenarioStep> steps = stepMapper.selectByScenarioIdOrderByStep(scenarioId);
        if (steps.isEmpty()) throw new IllegalArgumentException("scenario has no steps");

        log.info("scenario: 开始执行 scenarioId={} name={} steps={}", scenarioId, sc.getName(), steps.size());
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
                    log.warn("scenario: scenarioId={} 在 stepNo={} 失败中止（continueOnFailure=false）", scenarioId, s.getStepNo());
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
        log.info("scenario: 执行结束 scenarioId={} succeeded={} failed={} aborted={}",
                scenarioId, result.succeeded, result.failed, result.aborted);
        return result;
    }

    /** 场景执行的汇总结果。 */
    public static class RunResult {
        public Long scenarioId;
        public int total;
        public int succeeded;
        public int failed;
        public boolean aborted;
        /** 中止发生在哪一步（null 表示未中止）。 */
        public Integer abortedAtStep;
        public List<Long> historyIds = new ArrayList<>();
        public List<RunEntry> entries;
    }

    /** 场景里单步的执行明细。 */
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