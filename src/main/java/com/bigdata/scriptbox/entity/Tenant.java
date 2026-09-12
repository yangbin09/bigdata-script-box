package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户。
 *
 * <p>脚本执行时按租户加载对应 Kerberos 主体 + keytab。脚本通过环境变量
 * {@code KRB5_PRINCIPAL} / {@code KEYTAB_PATH} 等使用。
 */
@Data
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
    /** V3 (PR-0): 认证名称 = Principal 别名，存在 UNIQUE 约束，与 principal 一一对应。 */
    private String authName;
    /** V3 (PR-0): 最近一次认证测试时间（用于 UI 直接显示 "待重新测试" 等状态）。 */
    private LocalDateTime lastTestAt;
    /** V3 (PR-0): 最近一次认证测试是否通过；null 表示从未测试。 */
    private Boolean lastTestOk;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;
}