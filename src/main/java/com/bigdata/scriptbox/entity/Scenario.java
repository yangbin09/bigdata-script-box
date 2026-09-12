package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 场景编排。
 *
 * <p>一组有序步骤的封装，每步绑定一个脚本（可选 Preset），默认遇错即停，
 * 步骤可独立配置 {@code continueOnFailure=true}。
 */
@Data
@TableName("scenario")
public class Scenario {
    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 场景名。 */
    private String name;
    /** 场景描述。 */
    private String description;
    /** 分类（例如 ETL / 数据治理 / 报表）。 */
    private String category;
    /** 是否启用。 */
    private Boolean enabled;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;
}