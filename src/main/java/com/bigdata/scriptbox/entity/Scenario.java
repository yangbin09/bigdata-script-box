package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 场景编排。
 *
 * <p>一组有序步骤的封装，每步绑定一个脚本（可选 Preset），默认遇错即停，
 * 步骤可独立配置 {@code continueOnFailure=true}。
 */
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

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 场景名 */
    public String getName() { return name; }
    /** @param name 场景名 */
    public void setName(String name) { this.name = name; }
    /** @return 描述 */
    public String getDescription() { return description; }
    /** @param description 描述 */
    public void setDescription(String description) { this.description = description; }
    /** @return 分类 */
    public String getCategory() { return category; }
    /** @param category 分类 */
    public void setCategory(String category) { this.category = category; }
    /** @return 是否启用 */
    public Boolean getEnabled() { return enabled; }
    /** @param enabled 是否启用 */
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    /** @return 创建时间 */
    public LocalDateTime getCreateTime() { return createTime; }
    /** @param createTime 创建时间 */
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    /** @return 更新时间 */
    public LocalDateTime getUpdateTime() { return updateTime; }
    /** @param updateTime 更新时间 */
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}