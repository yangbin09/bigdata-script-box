package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.Scenario;
import org.apache.ibatis.annotations.Mapper;

/**
 * 场景表 Mapper。
 *
 * <p>对应表 {@code scenario}。{@code @Mapper} 注解让 MyBatis-Plus 在启动
 * 阶段扫描并注入此接口的代理实现。
 */
@Mapper
public interface ScenarioMapper extends BaseMapper<Scenario> {
}