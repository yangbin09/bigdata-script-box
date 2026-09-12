package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 脚本实体。
 *
 * <p>代表一个可被执行的 shell 脚本。
 *
 * <p>关键字段：
 * <ul>
 *   <li>{@link #scriptPath} — 服务端落盘相对路径（写入 {@code scripts/} 受控目录）；</li>
 *   <li>{@link #riskLevel} — READ_ONLY（默认） / WRITE / DANGEROUS。DANGEROUS 要求用户在前端输入 CONFIRM 才执行；</li>
 *   <li>{@link #allowConcurrent} — 同脚本同租户是否允许并发执行；默认 false 时，相同 (scriptId, tenantId) 已存在执行中的行会阻塞新请求；</li>
 *   <li>{@link #precheckConfigJson} — PreCheck 配置 JSON，含 Kerberos / 命令 / 文件 / 可写目录检查。</li>
 * </ul>
 */
@Data
@TableName("script")
public class Script {
    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 唯一名（标识 / 导入 zip 包内文件名）。 */
    private String name;
    /** 显示名（前端卡片用）。 */
    private String displayName;
    /** 分类。 */
    private String category;
    /** 描述。 */
    private String description;
    /** 服务端落盘的相对路径（相对于 {@code scriptbox.scripts-dir}）。 */
    private String scriptPath;
    /** 单次执行超时（秒）。 */
    private Integer timeoutSeconds;
    /** 是否启用。 */
    private Boolean enabled;
    /** 是否收藏（首页置顶用）。 */
    private Boolean favorite;
    /** 默认租户 ID。 */
    private Long defaultTenantId;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;
    /** PreCheck 配置 JSON（Kerberos / 命令 / 文件 / 可写目录）。 */
    private String precheckConfigJson;
    /** V2: 风险等级 — READ_ONLY（默认） / WRITE / DANGEROUS。DANGEROUS 执行前用户必须输入 CONFIRM。 */
    private String riskLevel;
    /** V2: 是否允许同脚本同租户并发；默认 false 时重复请求会被拒绝。 */
    private Boolean allowConcurrent;
}