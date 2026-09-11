package com.bigdata.scriptbox.dto;

/**
 * 统一 API 响应包装。
 *
 * <p>所有 REST 接口都返回本类；前端约定：
 * <ul>
 *   <li>{@code code == 0} — 成功；</li>
 *   <li>{@code code != 0} — 失败，{@code message} 给可读原因；</li>
 *   <li>{@code data} — 业务负载（成功时携带）。</li>
 * </ul>
 */
public class ApiResponse<T> {
    /** 业务返回码：0 表示成功，其它值表示失败。 */
    private int code;
    /** 人类可读的提示信息（错误时为原因，成功时通常为 "ok"）。 */
    private String message;
    /** 业务负载。 */
    private T data;

    /** Jackson 反序列化用的空构造。 */
    public ApiResponse() {}

    /**
     * 全字段构造。
     *
     * @param code 业务返回码
     * @param message 提示信息
     * @param data 负载
     */
    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 构造一个成功响应。
     *
     * @param data 业务负载（允许为 null）
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    /**
     * 构造一个无负载的成功响应。
     */
    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    /**
     * 构造一个失败响应（code 默认为 1）。
     *
     * @param message 错误描述
     */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(1, message, null);
    }

    /** @return 业务返回码 */
    public int getCode() { return code; }
    /** @param code 业务返回码 */
    public void setCode(int code) { this.code = code; }
    /** @return 提示信息 */
    public String getMessage() { return message; }
    /** @param message 提示信息 */
    public void setMessage(String message) { this.message = message; }
    /** @return 业务负载 */
    public T getData() { return data; }
    /** @param data 业务负载 */
    public void setData(T data) { this.data = data; }
}