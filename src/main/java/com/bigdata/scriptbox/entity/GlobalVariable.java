package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Site-wide variable injected into ProcessBuilder environment before every
 * script execution. sensitive=true means the value is masked in logs / UI.
 */
@TableName("global_variable")
public class GlobalVariable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String variableKey;
    private String variableValue;
    private String description;
    private Boolean sensitive;
    private Boolean enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVariableKey() { return variableKey; }
    public void setVariableKey(String variableKey) { this.variableKey = variableKey; }
    public String getVariableValue() { return variableValue; }
    public void setVariableValue(String variableValue) { this.variableValue = variableValue; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getSensitive() { return sensitive; }
    public void setSensitive(Boolean sensitive) { this.sensitive = sensitive; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}