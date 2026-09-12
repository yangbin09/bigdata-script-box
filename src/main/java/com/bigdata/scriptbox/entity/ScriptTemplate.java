package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

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
@Data
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
}