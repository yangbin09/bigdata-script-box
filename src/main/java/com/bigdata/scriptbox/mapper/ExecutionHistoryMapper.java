package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ExecutionHistoryMapper extends BaseMapper<ExecutionHistory> {
    /** Return all history rows for a batchId, ordered by row index then start time. */
    List<ExecutionHistory> selectByBatchId(@org.apache.ibatis.annotations.Param("batchId") String batchId);
}