package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.CleanupHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 清理历史表 Mapper。
 *
 * <p>对应表 {@code cleanup_history}。Service 层通过它写入每次手动清理的
 * 审计行，并查询"最近 N 条历史"用于设置页展示。
 */
@Mapper
public interface CleanupHistoryMapper extends BaseMapper<CleanupHistory> {
}