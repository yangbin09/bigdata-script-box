package com.bigdata.scriptbox.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * V3 (PR-5): 快捷操作 — 脚本 + 租户 + 参数组合，首页一键打开。
 *
 * <p>params_json 是 JSON 字符串（{@code {"param":"value"}}），与
 * {@code ScriptPreset.params_json} 保持一致。preset_id 与 params_json 二选一；
 * 同时存在时优先 preset。
 *
 * <p>sort_order 越小越靠前；创建时取 {@code MAX(sort_order)+1}。
 */
@Data
public class QuickAction {
    private Long id;
    private String name;
    private String icon;
    private Long scriptId;
    private Long tenantId;
    private String paramsJson;
    private Long presetId;
    private Integer sortOrder;
    private String createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}