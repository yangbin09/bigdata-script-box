package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 租户。
 *
 * <p>脚本执行时按租户加载对应 Kerberos 主体 + keytab。脚本通过环境变量
 * {@code KRB5_PRINCIPAL} / {@code KEYTAB_PATH} 等使用。
 */
@TableName("tenant")
public class Tenant {
    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户显示名（前端用）。 */
    private String name;
    /** Kerberos 主体，例如 {@code hdfs@EXAMPLE.COM}。 */
    private String principal;
    /** keytab 文件路径（服务端相对路径 / 受控目录）。 */
    private String keytabPath;
    /** 默认数据库（hive 场景）。 */
    private String defaultDatabase;
    /** 描述。 */
    private String description;
    /** 是否启用。 */
    private Boolean enabled;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;

    /** @return 主键 */
    public Long getId() { return id; }
    /** @param id 主键 */
    public void setId(Long id) { this.id = id; }
    /** @return 租户显示名 */
    public String getName() { return name; }
    /** @param name 租户显示名 */
    public void setName(String name) { this.name = name; }
    /** @return Kerberos 主体 */
    public String getPrincipal() { return principal; }
    /** @param principal Kerberos 主体 */
    public void setPrincipal(String principal) { this.principal = principal; }
    /** @return keytab 路径 */
    public String getKeytabPath() { return keytabPath; }
    /** @param keytabPath keytab 路径 */
    public void setKeytabPath(String keytabPath) { this.keytabPath = keytabPath; }
    /** @return 默认数据库 */
    public String getDefaultDatabase() { return defaultDatabase; }
    /** @param defaultDatabase 默认数据库 */
    public void setDefaultDatabase(String defaultDatabase) { this.defaultDatabase = defaultDatabase; }
    /** @return 描述 */
    public String getDescription() { return description; }
    /** @param description 描述 */
    public void setDescription(String description) { this.description = description; }
    /** @return 是否启用 */
    public Boolean getEnabled() { return enabled; }
    /** @param enabled 是否启用 */
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    /** @return 创建时间 */
    public LocalDateTime getCreateTime() { return createTime; }
    /** @param createTime 创建时间 */
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    /** @return 更新时间 */
    public LocalDateTime getUpdateTime() { return updateTime; }
    /** @param updateTime 更新时间 */
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}