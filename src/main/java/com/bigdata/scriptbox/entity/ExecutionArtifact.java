package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V2: 单次执行产生的一个产物文件。
 *
 * <p>脚本通过环境变量 {@code ARTIFACT_DIR}（值固定为
 * {@code <executionsDir>/<executionId>/artifacts/}）输出产物；运行结束后
 * 服务端扫描该目录，每个常规文件落一条本表。重跑时同 {@link #name} 的行会
 * 被替换而非追加，因此表不会跨次运行陈旧堆积。
 */
@Data
@TableName("execution_artifact")
public class ExecutionArtifact {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属执行 ID（对应 {@code execution_history.id}）。 */
    @TableField("execution_id")
    private Long executionId;
    /** 相对路径，例如 {@code artifacts/report.csv}。 */
    private String name;
    /** 服务端绝对路径；不会原样回给前端（前端只通过 download 接口下载）。 */
    private String path;
    /** 文件大小（字节）。 */
    @TableField("size_bytes")
    private Long sizeBytes;
    /** SHA-256 哈希值，用于校验完整性。 */
    private String sha256;
    /** MIME 类型（基于扩展名推断）。 */
    @TableField("mime_type")
    private String mimeType;
    /** 落盘时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}