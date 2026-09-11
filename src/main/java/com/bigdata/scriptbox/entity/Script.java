package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("script")
public class Script {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String displayName;
    private String category;
    private String description;
    private String scriptPath;
    private Integer timeoutSeconds;
    private Boolean enabled;
    private Boolean favorite;
    private Long defaultTenantId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** JSON config of pre-execution checks: kerberos/commands/files/writable dirs. */
    private String precheckConfigJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getScriptPath() { return scriptPath; }
    public void setScriptPath(String scriptPath) { this.scriptPath = scriptPath; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getFavorite() { return favorite; }
    public void setFavorite(Boolean favorite) { this.favorite = favorite; }
    public Long getDefaultTenantId() { return defaultTenantId; }
    public void setDefaultTenantId(Long defaultTenantId) { this.defaultTenantId = defaultTenantId; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public String getPrecheckConfigJson() { return precheckConfigJson; }
    public void setPrecheckConfigJson(String precheckConfigJson) { this.precheckConfigJson = precheckConfigJson; }
}