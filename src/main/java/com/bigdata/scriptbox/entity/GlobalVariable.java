package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全局变量。
 *
 * <p>每次脚本执行前，服务端会把本表中"启用"且"sensitive=false"的条目注入到
 * {@link ProcessBuilder} 的环境里。{@code sensitive=true} 的条目仍会注入到
 * 进程环境（脚本要用），但在日志与 API 详情接口里只显示遮罩后的占位串
 * （由 {@link com.bigdata.scriptbox.service.SensitiveDataMasker} 处理）。
 */
@Data
@TableName("global_variable")
public class GlobalVariable {
    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 环境变量名（写入 {@code ProcessBuilder.environment()}）。 */
    private String variableKey;
    /** 环境变量值。 */
    private String variableValue;
    /** 描述（前端展示用）。 */
    private String description;
    /** 是否敏感（仅控制日志 / 接口显示遮罩）。 */
    private Boolean sensitive;
    /** 是否启用。 */
    private Boolean enabled;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;
}