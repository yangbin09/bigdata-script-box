package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.ScenarioStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 场景步骤表 Mapper。
 *
 * <p>对应表 {@code scenario_step}。提供按场景 ID 排序查询与按场景 ID 批量
 * 删除两个便捷方法，分别用于「替换步骤」流程的读 / 删。
 */
@Mapper
public interface ScenarioStepMapper extends BaseMapper<ScenarioStep> {
    /**
     * 查询某场景的全部步骤，按步骤序号升序。
     *
     * @param scenarioId 场景 ID
     * @return 步骤列表
     */
    List<ScenarioStep> selectByScenarioIdOrderByStep(@Param("scenarioId") Long scenarioId);

    /**
     * 删除某场景的全部步骤。
     *
     * @param scenarioId 场景 ID
     * @return 删除行数
     */
    int deleteByScenarioId(@Param("scenarioId") Long scenarioId);
}