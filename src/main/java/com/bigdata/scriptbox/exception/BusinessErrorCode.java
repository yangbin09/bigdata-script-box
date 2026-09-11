package com.bigdata.scriptbox.exception;

/**
 * 统一业务错误码。
 *
 * <p>前端可见的稳定字符串，用于区分不同的业务错误。与 HTTP 状态码解耦：
 * 所有业务错误统一返回 HTTP 200，由 {@code code} 字段表达具体语义。
 *
 * <p>命名规则：
 * <ul>
 *   <li>{@code *_NOT_FOUND} — 资源不存在</li>
 *   <li>{@code *_DISABLED} — 资源被禁用</li>
 *   <li>{@code *_INVALID} — 输入校验失败</li>
 *   <li>{@code *_CONFLICT} — 状态冲突（如并发、唯一性）</li>
 *   <li>{@code INTERNAL_*} — 服务内部不可恢复错误</li>
 * </ul>
 */
public enum BusinessErrorCode {
    // 资源不存在
    SCRIPT_NOT_FOUND,
    TENANT_NOT_FOUND,
    PRESET_NOT_FOUND,
    VERSION_NOT_FOUND,
    EXECUTION_NOT_FOUND,
    SCENARIO_NOT_FOUND,
    ARTIFACT_NOT_FOUND,
    GLOBAL_VARIABLE_NOT_FOUND,
    TEMPLATE_NOT_FOUND,

    // 资源被禁用
    SCRIPT_DISABLED,
    TENANT_DISABLED,
    SCENARIO_DISABLED,

    // 输入校验失败
    PARAMETER_INVALID,
    PARAMETER_REQUIRED,
    RISK_LEVEL_INVALID,
    VARIABLE_KEY_INVALID,
    FILE_INPUT_INVALID,
    SYNTAX_CHECK_FAILED,

    // 状态冲突
    EXECUTION_ALREADY_RUNNING,
    EXECUTION_SLOT_LIMIT_REACHED,
    CONFIRM_TOKEN_MISMATCH,
    PREVIEW_EXPIRED,
    NAME_ALREADY_EXISTS,

    // 危险操作保护
    DANGEROUS_CONFIRM_REQUIRED,

    // PreCheck
    PRECHECK_FAILED,

    // 安全 / 路径
    PATH_INVALID,
    PATH_ESCAPE,

    // 内部错误
    INTERNAL_IO_ERROR,
    INTERNAL_ERROR,
    INTERNAL_NOT_IMPLEMENTED
}
