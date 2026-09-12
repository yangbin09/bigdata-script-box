package com.bigdata.scriptbox.exception;

import com.bigdata.scriptbox.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 全局异常处理（V3）。
 *
 * <p>目标（#9）：
 * <ol>
 *   <li>任何 Controller 不再需要自己写 try/catch；抛 {@link BusinessException}
 *       即可由本类统一映射成 {@link ApiResponse}。</li>
 *   <li>前端可读 {@code errorCode}（{@code BusinessErrorCode} 枚举名）做精确分流，
 *       不再依赖 message 文本解析（{@code "PREVIEW_EXPIRED: "} 字符串前缀已删除）。</li>
 *   <li>异常堆栈仅落盘到应用日志，不返回浏览器。</li>
 * </ol>
 *
 * <p>迁移约定（#9）：
 * <ul>
 *   <li>业务校验失败 → 抛 {@code BusinessException(code, message)}；
 *       advice 把枚举名落到 {@link ApiResponse#errorCode}。</li>
 *   <li>尚未迁移的 {@code IllegalArgumentException} / {@code IllegalStateException}
 *       仍自动归类为 {@code INTERNAL_ERROR}，保证未迁移代码不会崩。</li>
 *   <li>{@link com.bigdata.scriptbox.service.PreviewStore.PreviewExpiredException}
 *       单独映射为 {@code PREVIEW_EXPIRED}。</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** 业务异常 → ApiResponse(code=1, errorCode=枚举名, message=...)。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("业务异常 code={} message={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    /**
     * Preview 过期：保留单独的 handler 是为了避免清理侧改抛 {@link BusinessException}
     * 引入循环依赖（{@code PreviewStore} 在 service 包，不应反向依赖 exception 包）。
     * 见 {@code PreviewStore.PREVIEW_EXPIRED}。
     */
    @ExceptionHandler(com.bigdata.scriptbox.service.PreviewStore.PreviewExpiredException.class)
    public ResponseEntity<ApiResponse<Void>> handlePreviewExpired(
            com.bigdata.scriptbox.service.PreviewStore.PreviewExpiredException ex) {
        log.warn("清理预览过期: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(BusinessErrorCode.PREVIEW_EXPIRED, ex.getMessage()));
    }

    /**
     * 参数非法（迁移期兼容）：降级为 INTERNAL_ERROR；message 保留以方便排障。
     * 业务代码应改抛 {@link BusinessException}。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("参数非法（待迁移到 BusinessException）: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(BusinessErrorCode.INTERNAL_ERROR, ex.getMessage()));
    }

    /** 状态非法（迁移期兼容）：同 {@link #handleIllegalArgument}。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("状态非法（待迁移到 BusinessException）: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(BusinessErrorCode.INTERNAL_ERROR, ex.getMessage()));
    }

    /** 兜底：任何未捕获异常 → HTTP 500 + 通用消息，stack trace 落日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAll(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(BusinessErrorCode.INTERNAL_ERROR,
                        "服务器内部错误: " + ex.getClass().getSimpleName()));
    }

    /** Bean Validation 失败 → 返回更友好的 400 + 字段错误清单。 */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<List<FieldError>>> handleValidation(
            org.springframework.web.bind.MethodArgumentNotValidException ex) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        log.warn("参数校验失败: {}", errors);
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(BusinessErrorCode.PARAMETER_INVALID, "参数校验失败", errors));
    }

    /** 字段错误视图（与 Bean Validation 错误一一对应）。 */
    public record FieldError(String field, String message) {}
}