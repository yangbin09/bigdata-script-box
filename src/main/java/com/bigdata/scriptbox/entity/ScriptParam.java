package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 脚本参数定义。
 *
 * <p>每一行声明一个参数：名称、类型（text/number/select/boolean/date/textarea）、
 * 默认值、选项、是否必填、占位符、帮助文本等。前端执行抽屉根据这些信息渲染
 * 表单；服务端会用 {@link #defaultValue} 作为兜底，缺值时自动补上。
 *
 * <p>V2: {@link #visibleWhenJson} 是单条条件可见规则，结构：
 * {@code {"param":"env","operator":"equals|notEquals","value":"prod"}}；
 * 空表示"始终可见"。
 */
@TableName("script_param")
public class ScriptParam {
    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属脚本 ID。 */
    private Long scriptId;
    /** 命令行参数名（例如 database）。 */
    private String name;
    /** 显示标签。 */
    private String label;
    /** 类型：text | number | select | boolean | date | textarea。 */
    private String type;
    /** 默认值。 */
    private String defaultValue;
    /** 选项（select 类型时为逗号分隔）。 */
    private String options;
    /** 是否必填。 */
    private Boolean required;
    /** 排序序号。 */
    private Integer sortOrder;
    /** 占位提示文字。 */
    private String placeholder;
    /** 帮助说明。 */
    private String helpText;
    /** V2: 单条条件可见规则；空表示始终可见。 */
    private String visibleWhenJson;

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 所属脚本 ID */
    public Long getScriptId() { return scriptId; }
    /** @param scriptId 所属脚本 ID */
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }
    /** @return 命令行参数名 */
    public String getName() { return name; }
    /** @param name 命令行参数名 */
    public void setName(String name) { this.name = name; }
    /** @return 显示标签 */
    public String getLabel() { return label; }
    /** @param label 显示标签 */
    public void setLabel(String label) { this.label = label; }
    /** @return 类型 */
    public String getType() { return type; }
    /** @param type 类型 */
    public void setType(String type) { this.type = type; }
    /** @return 默认值 */
    public String getDefaultValue() { return defaultValue; }
    /** @param defaultValue 默认值 */
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    /** @return 选项（逗号分隔） */
    public String getOptions() { return options; }
    /** @param options 选项 */
    public void setOptions(String options) { this.options = options; }
    /** @return 是否必填 */
    public Boolean getRequired() { return required; }
    /** @param required 是否必填 */
    public void setRequired(Boolean required) { this.required = required; }
    /** @return 排序 */
    public Integer getSortOrder() { return sortOrder; }
    /** @param sortOrder 排序 */
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    /** @return 占位符 */
    public String getPlaceholder() { return placeholder; }
    /** @param placeholder 占位符 */
    public void setPlaceholder(String placeholder) { this.placeholder = placeholder; }
    /** @return 帮助文本 */
    public String getHelpText() { return helpText; }
    /** @param helpText 帮助文本 */
    public void setHelpText(String helpText) { this.helpText = helpText; }
    /** @return 条件可见规则 JSON */
    public String getVisibleWhenJson() { return visibleWhenJson; }
    /** @param visibleWhenJson 条件可见规则 JSON */
    public void setVisibleWhenJson(String visibleWhenJson) { this.visibleWhenJson = visibleWhenJson; }
}