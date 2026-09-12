package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V2: 手动数据清理审计行。
 *
 * <p>每次 {@link com.bigdata.scriptbox.service.CleanupService} 执行一次手动清理，
 * 无论实际删了多少文件 / 行，都写入一条本表。用于「Settings → Data Cleanup」
 * 页面底部「最近清理历史」列表，以及回答"上周二 03:14 哪些数据被删了"。
 */
@Data
@TableName("cleanup_history")
public class CleanupHistory {

    /** 清理结果：完全成功。 */
    public static final String RESULT_SUCCESS = "SUCCESS";
    /** 清理结果：部分成功（有跳过或失败项）。 */
    public static final String RESULT_PARTIAL = "PARTIAL";
    /** 清理结果：整体失败。 */
    public static final String RESULT_FAILED  = "FAILED";

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 执行时间（UTC 存库，按本地时区展示）。 */
    @TableField("created_at")
    private LocalDateTime createdAt;
    /** 对应的预览快照 UUID。 */
    @TableField("preview_id")
    private String previewId;
    /** 执行时使用的保留天数字典（JSON 字符串）。 */
    @TableField("retention_json")
    private String retentionJson;
    /** 执行结果（SUCCESS / PARTIAL / FAILED）。 */
    private String result;
    /** 删除的执行目录个数。 */
    @TableField("execution_deleted")
    private Integer executionDeleted;
    /** 删除的 artifact 个数。 */
    @TableField("artifact_deleted")
    private Integer artifactDeleted;
    /** 删除的日志文件个数。 */
    @TableField("log_deleted")
    private Integer logDeleted;
    /** 删除的 execution_history 行数。 */
    @TableField("history_deleted")
    private Integer historyDeleted;
    /** 释放的磁盘字节数。 */
    @TableField("bytes_freed")
    private Long bytesFreed;
    /** 跳过条数（被锁、权限等）。 */
    @TableField("skipped_count")
    private Integer skippedCount;
    /** 失败条数。 */
    @TableField("failed_count")
    private Integer failedCount;
    /** 备注信息（错误原因等）。 */
    private String message;
}