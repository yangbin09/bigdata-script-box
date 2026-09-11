package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * V2: 单次执行产生的一个产物文件。
 *
 * <p>脚本通过环境变量 {@code ARTIFACT_DIR}（值固定为
 * {@code <executionsDir>/<executionId>/artifacts/}）输出产物；运行结束后
 * 服务端扫描该目录，每个常规文件落一条本表。重跑时同 {@link #name} 的行会
 * 被替换而非追加，因此表不会跨次运行陈旧堆积。
 */
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

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 所属执行 ID */
    public Long getExecutionId() { return executionId; }
    /** @param executionId 所属执行 ID */
    public void setExecutionId(Long executionId) { this.executionId = executionId; }
    /** @return 相对路径 */
    public String getName() { return name; }
    /** @param name 相对路径 */
    public void setName(String name) { this.name = name; }
    /** @return 绝对路径 */
    public String getPath() { return path; }
    /** @param path 绝对路径 */
    public void setPath(String path) { this.path = path; }
    /** @return 文件字节数 */
    public Long getSizeBytes() { return sizeBytes; }
    /** @param sizeBytes 文件字节数 */
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    /** @return SHA-256 哈希 */
    public String getSha256() { return sha256; }
    /** @param sha256 SHA-256 哈希 */
    public void setSha256(String sha256) { this.sha256 = sha256; }
    /** @return MIME 类型 */
    public String getMimeType() { return mimeType; }
    /** @param mimeType MIME 类型 */
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    /** @return 落盘时间 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** @param createdAt 落盘时间 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}