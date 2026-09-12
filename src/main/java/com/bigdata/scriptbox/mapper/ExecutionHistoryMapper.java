package com.bigdata.scriptbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.scriptbox.entity.ExecutionHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
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

    /**
     * V3 (PR-0): 拉取所有未结束的执行（PENDING / RUNNING），用于：
     * <ul>
     *   <li>应用启动时 {@link com.bigdata.scriptbox.service.StartupReconciler}
     *       一次性扫表改写为 INTERRUPTED；</li>
     *   <li>前端 {@code /api/executions/recent-active} 聚合活跃任务时，
     *       与内存 {@link com.bigdata.scriptbox.service.ExecutionGate} 合并显示。</li>
     * </ul>
     * 服务重启后内存 Permit 已清空，但 DB 里残留的 RUNNING 行只有在拉这张视图后
     * 才会进入前端任务中心（否则历史会一直"挂"在那里直到 Cleanup 触发）。
     */
    List<ExecutionHistory> selectActive();

    /**
     * V3 (PR-0): 批量把历史行改写为 INTERRUPTED，由 StartupReconciler 调用。
     *
     * @param ids     需要改写的历史行 ID 列表
     * @param reason  写入 {@code interrupted_reason} 的简短原因
     *                （如 {@code "process_restart"}）
     * @param endTime 写入 {@code end_time} 的截止时间
     * @return 受影响行数
     */
    int markInterrupted(@Param("ids") List<Long> ids,
                        @Param("reason") String reason,
                        @Param("endTime") LocalDateTime endTime);
}