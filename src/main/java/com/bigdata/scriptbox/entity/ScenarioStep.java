package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 场景步骤。
 *
 * <p>一个场景包含若干有序步骤；每步绑定一个脚本，并可选地引用 Preset 来
 * 自动应用参数。{@code continueOnFailure=true} 让本步失败时不中断后续步骤。
 */
@TableName("scenario_step")
public class ScenarioStep {
    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属场景 ID。 */
    private Long scenarioId;
    /** 步骤序号（1-based）。 */
    private Integer stepNo;
    /** 脚本 ID。 */
    private Long scriptId;
    /** 预设 ID（可空）。 */
    private Long presetId;
    /** 本步失败后是否继续（默认 false）。 */
    private Boolean continueOnFailure;
    /** 创建时间。 */
    private LocalDateTime createTime;

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 所属场景 ID */
    public Long getScenarioId() { return scenarioId; }
    /** @param scenarioId 所属场景 ID */
    public void setScenarioId(Long scenarioId) { this.scenarioId = scenarioId; }
    /** @return 步骤序号 */
    public Integer getStepNo() { return stepNo; }
    /** @param stepNo 步骤序号 */
    public void setStepNo(Integer stepNo) { this.stepNo = stepNo; }
    /** @return 脚本 ID */
    public Long getScriptId() { return scriptId; }
    /** @param scriptId 脚本 ID */
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }
    /** @return 预设 ID */
    public Long getPresetId() { return presetId; }
    /** @param presetId 预设 ID */
    public void setPresetId(Long presetId) { this.presetId = presetId; }
    /** @return 是否遇错继续 */
    public Boolean getContinueOnFailure() { return continueOnFailure; }
    /** @param continueOnFailure 是否遇错继续 */
    public void setContinueOnFailure(Boolean continueOnFailure) { this.continueOnFailure = continueOnFailure; }
    /** @return 创建时间 */
    public LocalDateTime getCreateTime() { return createTime; }
    /** @param createTime 创建时间 */
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}