package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 全局变量。
 *
 * <p>每次脚本执行前，服务端会把本表中"启用"且"sensitive=false"的条目注入到
 * {@link ProcessBuilder} 的环境里。{@code sensitive=true} 的条目仍会注入到
 * 进程环境（脚本要用），但在日志与 API 详情接口里只显示遮罩后的占位串
 * （由 {@link com.bigdata.scriptbox.service.SensitiveDataMasker} 处理）。
 */
@TableName("global_variable")
public class GlobalVariable {
    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 环境变量名（写入 {@code ProcessBuilder.environment()}）。 */
    private String variableKey;
    /** 环境变量值。 */
    private String variableValue;
    /** 描述（前端展示用）。 */
    private String description;
    /** 是否敏感（仅控制日志 / 接口显示遮罩）。 */
    private Boolean sensitive;
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
    /** @return 变量名 */
    public String getVariableKey() { return variableKey; }
    /** @param variableKey 变量名 */
    public void setVariableKey(String variableKey) { this.variableKey = variableKey; }
    /** @return 变量值 */
    public String getVariableValue() { return variableValue; }
    /** @param variableValue 变量值 */
    public void setVariableValue(String variableValue) { this.variableValue = variableValue; }
    /** @return 描述 */
    public String getDescription() { return description; }
    /** @param description 描述 */
    public void setDescription(String description) { this.description = description; }
    /** @return 是否敏感 */
    public Boolean getSensitive() { return sensitive; }
    /** @param sensitive 是否敏感 */
    public void setSensitive(Boolean sensitive) { this.sensitive = sensitive; }
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