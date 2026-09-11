package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Pre-saved parameter set for a script. params_json holds the full param map
 * as captured at the moment the preset was saved. params are validated lazily
 * against the current ScriptParam declarations: missing keys fall back to
 * defaultValue; keys no longer in the schema are silently ignored.
 */
@TableName("script_preset")
public class ScriptPreset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scriptId;
    private String name;
    private String paramsJson;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}