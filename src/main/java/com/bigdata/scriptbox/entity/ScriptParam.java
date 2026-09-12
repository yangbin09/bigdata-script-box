package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

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
@Data
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
}