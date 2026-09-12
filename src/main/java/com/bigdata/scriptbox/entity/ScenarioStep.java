package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 场景步骤。
 *
 * <p>一个场景包含若干有序步骤；每步绑定一个脚本，并可选地引用 Preset 来
 * 自动应用参数。{@code continueOnFailure=true} 让本步失败时不中断后续步骤。
 */
@Data
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
}