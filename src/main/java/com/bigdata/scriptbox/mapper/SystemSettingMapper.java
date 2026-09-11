package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.SystemSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统设置表 Mapper。
 *
 * <p>对应表 {@code system_setting}。键即主键（{@code setting_key}）。
 */
@Mapper
public interface SystemSettingMapper extends BaseMapper<SystemSetting> {
}