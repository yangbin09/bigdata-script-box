package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.ScriptTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 脚本模板表 Mapper。
 *
 * <p>对应表 {@code script_template}。模板由 {@code DataInitializer} 在
 * 启动时按 {@code script_template.yml} 种子数据写入。
 */
@Mapper
public interface ScriptTemplateMapper extends BaseMapper<ScriptTemplate> {
}