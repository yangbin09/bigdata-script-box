package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * V2: persisted runtime settings (key/value). Used by the cleanup scheduler
 * to override {@code application.yml} defaults without a restart, and to
 * surface any future runtime-tunable knobs.
 */
@TableName("system_setting")
public class SystemSetting {

    @TableId(type = IdType.ASSIGN_ID)
    private String settingKey;
    @TableField("setting_value")
    private String settingValue;
    private String description;
    @TableField("update_time")
    private LocalDateTime updateTime;

    public String getSettingKey() { return settingKey; }
    public void setSettingKey(String settingKey) { this.settingKey = settingKey; }
    public String getSettingValue() { return settingValue; }
    public void setSettingValue(String settingValue) { this.settingValue = settingValue; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}