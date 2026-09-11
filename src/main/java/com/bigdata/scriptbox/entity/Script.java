package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

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

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 唯一名 */
    public String getName() { return name; }
    /** @param name 唯一名 */
    public void setName(String name) { this.name = name; }
    /** @return 显示名 */
    public String getDisplayName() { return displayName; }
    /** @param displayName 显示名 */
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    /** @return 分类 */
    public String getCategory() { return category; }
    /** @param category 分类 */
    public void setCategory(String category) { this.category = category; }
    /** @return 描述 */
    public String getDescription() { return description; }
    /** @param description 描述 */
    public void setDescription(String description) { this.description = description; }
    /** @return 脚本路径（相对） */
    public String getScriptPath() { return scriptPath; }
    /** @param scriptPath 脚本路径 */
    public void setScriptPath(String scriptPath) { this.scriptPath = scriptPath; }
    /** @return 超时秒数 */
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    /** @param timeoutSeconds 超时秒数 */
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    /** @return 是否启用 */
    public Boolean getEnabled() { return enabled; }
    /** @param enabled 是否启用 */
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    /** @return 是否收藏 */
    public Boolean getFavorite() { return favorite; }
    /** @param favorite 是否收藏 */
    public void setFavorite(Boolean favorite) { this.favorite = favorite; }
    /** @return 默认租户 ID */
    public Long getDefaultTenantId() { return defaultTenantId; }
    /** @param defaultTenantId 默认租户 ID */
    public void setDefaultTenantId(Long defaultTenantId) { this.defaultTenantId = defaultTenantId; }
    /** @return 创建时间 */
    public LocalDateTime getCreateTime() { return createTime; }
    /** @param createTime 创建时间 */
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    /** @return 更新时间 */
    public LocalDateTime getUpdateTime() { return updateTime; }
    /** @param updateTime 更新时间 */
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    /** @return PreCheck 配置 JSON */
    public String getPrecheckConfigJson() { return precheckConfigJson; }
    /** @param precheckConfigJson PreCheck 配置 JSON */
    public void setPrecheckConfigJson(String precheckConfigJson) { this.precheckConfigJson = precheckConfigJson; }
    /** @return 风险等级 */
    public String getRiskLevel() { return riskLevel; }
    /** @param riskLevel 风险等级 */
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    /** @return 是否允许并发 */
    public Boolean getAllowConcurrent() { return allowConcurrent; }
    /** @param allowConcurrent 是否允许并发 */
    public void setAllowConcurrent(Boolean allowConcurrent) { this.allowConcurrent = allowConcurrent; }
}