package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.ScenarioStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ScenarioStepMapper extends BaseMapper<ScenarioStep> {
    List<ScenarioStep> selectByScenarioIdOrderByStep(@Param("scenarioId") Long scenarioId);
    int deleteByScenarioId(@Param("scenarioId") Long scenarioId);
}