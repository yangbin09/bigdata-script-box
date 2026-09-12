package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 脚本预设（Pre-saved parameter set）。
 *
 * <p>把一组常用参数值持久化下来，下次执行时一键应用。{@code paramsJson} 记录
 * 预设保存时的完整参数 Map；执行时按当前脚本的 {@link ScriptParam} 定义做懒
 * 校验：缺失键回退到 {@code defaultValue}，多余键静默忽略。
 */
@Data
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
}