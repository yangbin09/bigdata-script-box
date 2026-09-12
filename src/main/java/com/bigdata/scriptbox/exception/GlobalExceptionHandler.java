package com.bigdata.scriptbox.exception;

import com.bigdata.scriptbox.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 *
 * <p>目标：
 * <ol>
 *   <li>任何 Controller 不再需要自己写 try/catch；抛 {@link BusinessException}
 *       即可由本类统一映射成 {@link ApiResponse}。</li>
 *   <li>异常堆栈仅落盘到应用日志（{@code scriptbox.log}），不返回浏览器，
 *       避免泄露内部细节。</li>
 *   <li>前端只看到稳定的 {@code code}（枚举名）与中文 message，行为可预测。</li>
 * </ol>
 *
 * <p>兼容性：
 * <ul>
 *   <li>旧的 {@code IllegalArgumentException} / {@code IllegalStateException}
 *       仍然被自动归类为 {@code INTERNAL_ERROR}，保证现有 Service 在未迁移
 *       之前仍能正常工作。</li>
 *   <li>{@code PreviewStore.PreviewExpiredException} 由于已存在，单独映射为
 *       {@code PREVIEW_EXPIRED}。</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** 业务异常 → ApiResponse(code=1, message=...) */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("业务异常 code={} message={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /** 参数非法 → 视为业务异常，code=1。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("参数非法: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /** 状态非法 → 视为业务异常，code=1。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("状态非法: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /** 兜底：任何未捕获异常 → HTTP 500 + 通用消息，stack trace 落日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAll(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("服务器内部错误: " + ex.getClass().getSimpleName()));
    }
}
