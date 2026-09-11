package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * V2: 内置脚本模板。
 *
 * <p>服务端预置一组脚本骨架（plain-shell / kerberos / hive-示例等）。字段：
 * <ul>
 *   <li>{@link #code} — API 路径用的稳定标识；</li>
 *   <li>{@link #name} / {@link #description} / {@link #category} — 前端展示；</li>
 *   <li>{@link #content} — 字面 .sh 正文；</li>
 *   <li>{@link #paramsJson} — 可选的 ScriptParam 数组 JSON；新建脚本时一并复制。</li>
 * </ul>
 */
@TableName("script_template")
public class ScriptTemplate {
    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 模板编码（API 路径用，稳定）。 */
    private String code;
    /** 模板名。 */
    private String name;
    /** 分类。 */
    private String category;
    /** 描述。 */
    private String description;
    /** 脚本正文。 */
    private String content;
    /** ScriptParam 数组 JSON；空表示无参数。 */
    private String paramsJson;
    /** 排序。 */
    private Integer sortOrder;
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
    /** @return 模板编码 */
    public String getCode() { return code; }
    /** @param code 模板编码 */
    public void setCode(String code) { this.code = code; }
    /** @return 模板名 */
    public String getName() { return name; }
    /** @param name 模板名 */
    public void setName(String name) { this.name = name; }
    /** @return 分类 */
    public String getCategory() { return category; }
    /** @param category 分类 */
    public void setCategory(String category) { this.category = category; }
    /** @return 描述 */
    public String getDescription() { return description; }
    /** @param description 描述 */
    public void setDescription(String description) { this.description = description; }
    /** @return 脚本正文 */
    public String getContent() { return content; }
    /** @param content 脚本正文 */
    public void setContent(String content) { this.content = content; }
    /** @return ScriptParam 数组 JSON */
    public String getParamsJson() { return paramsJson; }
    /** @param paramsJson ScriptParam 数组 JSON */
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }
    /** @return 排序 */
    public Integer getSortOrder() { return sortOrder; }
    /** @param sortOrder 排序 */
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
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