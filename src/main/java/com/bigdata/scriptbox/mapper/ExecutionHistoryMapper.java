package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 执行历史表 Mapper。
 *
 * <p>对应表 {@code execution_history}，执行历史页面与批次 / 场景汇总都基于
 * 此表。本 Mapper 在 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper}
 * 基础上补充了按批次 ID 查询的便捷方法。
 */
@Mapper
public interface ExecutionHistoryMapper extends BaseMapper<ExecutionHistory> {
    /**
     * 查询某批次 ID 对应的所有历史行，按批次行号再按开始时间排序。
     *
     * @param batchId 批次 ID
     * @return 历史行列表（可能为空）
     */
    List<ExecutionHistory> selectByBatchId(@Param("batchId") String batchId);
}