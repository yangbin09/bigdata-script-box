package com.bigdata.scriptbox.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应包装。
 *
 * <p>所有 REST 接口都返回本类。前端约定（V3 起升级为双信号）：
 * <ul>
 *   <li>{@code code == 0} — 成功；</li>
 *   <li>{@code code != 0} — 失败，{@code message} 给可读原因；</li>
 *   <li>{@code data} — 业务负载（成功时携带）；</li>
 *   <li>{@code errorCode}（V3 新增）— 稳定的字符串错误码（{@code BusinessErrorCode} 枚举名），
 *       供前端做精确分支；为 {@code null} 表示成功。旧前端忽略此字段即可，向后兼容。</li>
 * </ul>
 *
 * <p>V3 引入 {@code errorCode} 是为了解决 #9 — 此前所有业务失败都返回
 * {@code code=1}，前端只能解析 message 文案。本次新增字段后，
 * {@code GlobalExceptionHandler} 把 {@code BusinessException} 的枚举名落到此字段，
 * 前端可渐进迁移到读 {@code errorCode}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ApiResponse<T> {
    /** 业务返回码：0 表示成功，其它值表示失败。V3 之前仅 0/1；后续保留 1 表示失败。 */
    private int code;
    /** 稳定的字符串错误码（V3 新增）；成功时为 null。仅在 {@code code != 0} 时有值。 */
    private String errorCode;
    /** 人类可读的提示信息（错误时为原因，成功时通常为 "ok"）。 */
    private String message;
    /** 业务负载。 */
    private T data;

    /**
     * 构造一个成功响应。
     *
     * @param data 业务负载（允许为 null）
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, null, "ok", data);
    }

    /**
     * 构造一个无负载的成功响应。
     */
    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, null, "ok", null);
    }

    /**
     * V3 兼容：构造一个失败响应（{@code errorCode=INTERNAL_ERROR}）。
     * 保留给尚未迁移到 {@code BusinessErrorCode} 的调用点；
     * 业务代码应改用 {@link #error(String, String)}。
     */
    public static <T> ApiResponse<T> error(String message) {
        return error(com.bigdata.scriptbox.exception.BusinessErrorCode.INTERNAL_ERROR, message);
    }

    /**
     * V3：构造一个带错误码的失败响应。
     *
     * @param errorCode {@code BusinessErrorCode} 枚举名（{@code name()}）；为 null 时
     *                  退化为 {@link #error(String)}（仅在迁移期临时出现）。
     * @param message   错误描述
     */
    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return new ApiResponse<>(1, errorCode, message, null);
    }

    /**
     * V3：构造一个带错误码的失败响应（直接接收枚举，避免 name() 拼写错误）。
     */
    public static <T> ApiResponse<T> error(com.bigdata.scriptbox.exception.BusinessErrorCode errorCode,
                                           String message) {
        return error(errorCode == null ? null : errorCode.name(), message);
    }

    /**
     * V3：构造一个带错误码 + 业务负载的失败响应。
     * 用于 Bean Validation 等"既要返回字段错误明细，也要让前端分流"的场景。
     */
    public static <T> ApiResponse<T> error(com.bigdata.scriptbox.exception.BusinessErrorCode errorCode,
                                           String message, T data) {
        return new ApiResponse<>(1, errorCode == null ? null : errorCode.name(), message, data);
    }
}