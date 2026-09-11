package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 脚本预设（Pre-saved parameter set）。
 *
 * <p>把一组常用参数值持久化下来，下次执行时一键应用。{@code paramsJson} 记录
 * 预设保存时的完整参数 Map；执行时按当前脚本的 {@link ScriptParam} 定义做懒
 * 校验：缺失键回退到 {@code defaultValue}，多余键静默忽略。
 */
@TableName("script_preset")
public class ScriptPreset {
    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属脚本 ID。 */
    private Long scriptId;
    /** 预设名。 */
    private String name;
    /** 参数 JSON。 */
    private String paramsJson;
    /** 描述。 */
    private String description;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 所属脚本 ID */
    public Long getScriptId() { return scriptId; }
    /** @param scriptId 所属脚本 ID */
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }
    /** @return 预设名 */
    public String getName() { return name; }
    /** @param name 预设名 */
    public void setName(String name) { this.name = name; }
    /** @return 参数 JSON */
    public String getParamsJson() { return paramsJson; }
    /** @param paramsJson 参数 JSON */
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }
    /** @return 描述 */
    public String getDescription() { return description; }
    /** @param description 描述 */
    public void setDescription(String description) { this.description = description; }
    /** @return 创建时间 */
    public LocalDateTime getCreateTime() { return createTime; }
    /** @param createTime 创建时间 */
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    /** @return 更新时间 */
    public LocalDateTime getUpdateTime() { return updateTime; }
    /** @param updateTime 更新时间 */
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}