package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 脚本正文历史快照。
 *
 * <p>每次保存脚本正文都会落一条本表行；回滚操作会基于旧版本内容再保存一次，
 * 产生新的 {@link #versionNo}，因此本表是 append-only 的可审计历史。
 */
@TableName("script_version")
public class ScriptVersion {
    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属脚本 ID。 */
    private Long scriptId;
    /** 版本号（从 1 开始递增）。 */
    private Integer versionNo;
    /** 脚本正文。 */
    private String scriptContent;
    /** 备注（提交信息等）。 */
    private String remark;
    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 所属脚本 ID */
    public Long getScriptId() { return scriptId; }
    /** @param scriptId 所属脚本 ID */
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }
    /** @return 版本号 */
    public Integer getVersionNo() { return versionNo; }
    /** @param versionNo 版本号 */
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    /** @return 脚本正文 */
    public String getScriptContent() { return scriptContent; }
    /** @param scriptContent 脚本正文 */
    public void setScriptContent(String scriptContent) { this.scriptContent = scriptContent; }
    /** @return 备注 */
    public String getRemark() { return remark; }
    /** @param remark 备注 */
    public void setRemark(String remark) { this.remark = remark; }
    /** @return 创建时间 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** @param createdAt 创建时间 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}