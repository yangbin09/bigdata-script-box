package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V2: 持久化的运行时设置（key/value）。
 *
 * <p>用于在不停机的情况下覆盖 {@code application.yml} 的默认值（主要是清理
 * 保留天数相关键），并承载后续可热更新的开关。
 */
@Data
@TableName("system_setting")
public class SystemSetting {

    /** 主键 = 设置键（字符串）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private String settingKey;
    /** 设置值（字符串形式，调用方按需转换类型）。 */
    @TableField("setting_value")
    private String settingValue;
    /** 描述。 */
    private String description;
    /** 更新时间。 */
    @TableField("update_time")
    private LocalDateTime updateTime;
}