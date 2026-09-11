package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("script_param")
public class ScriptParam {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scriptId;
    private String name;          // cli arg name (e.g. database)
    private String label;         // display label
    private String type;          // text | number | select | boolean | date | textarea
    private String defaultValue;
    private String options;       // comma-separated for select
    private Boolean required;
    private Integer sortOrder;
    private String placeholder;
    private String helpText;
    /** V2: a single conditional rule controlling whether this param is shown.
     *  Shape: {"param":"env","operator":"equals|notEquals","value":"prod"}.
     *  Null/empty means "always visible". Stored as JSON text. */
    private String visibleWhenJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }
    public Boolean getRequired() { return required; }
    public void setRequired(Boolean required) { this.required = required; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getPlaceholder() { return placeholder; }
    public void setPlaceholder(String placeholder) { this.placeholder = placeholder; }
    public String getHelpText() { return helpText; }
    public void setHelpText(String helpText) { this.helpText = helpText; }
    public String getVisibleWhenJson() { return visibleWhenJson; }
    public void setVisibleWhenJson(String visibleWhenJson) { this.visibleWhenJson = visibleWhenJson; }
}