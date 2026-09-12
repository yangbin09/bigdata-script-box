package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 脚本正文历史快照。
 *
 * <p>每次保存脚本正文都会落一条本表行；回滚操作会基于旧版本内容再保存一次，
 * 产生新的 {@link #versionNo}，因此本表是 append-only 的可审计历史。
 */
@Data
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
}