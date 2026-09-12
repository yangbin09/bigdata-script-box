package com.bigdata.scriptbox.exception;

/**
 * 业务异常。
 *
 * <p>统一承载业务层面的可预期错误（资源缺失、参数非法、并发冲突等）。
 * 全局异常处理器（{@link GlobalExceptionHandler}）会把它转换成
 * {@code ApiResponse(code=1, errorCode=枚举名, message=...)} 返回给前端。
 *
 * <p>V3（#9）继承 {@link IllegalArgumentException}（而非裸 {@link RuntimeException}），
 * 出于迁移期兼容：旧代码里 {@code catch (IllegalArgumentException)} 仍然能接住
 * {@code BusinessException}，新代码可继续写更具体的 {@code catch (BusinessException)}。
 * Spring 的 {@code @ExceptionHandler} 解析也会优先匹配更具体的
 * {@code BusinessException.class}，行为正确。
 *
 * <p>为什么需要业务异常而不是直接抛 {@link IllegalArgumentException}：
 * <ol>
 *   <li>业务异常携带稳定的 {@link BusinessErrorCode}，前端可基于 {@code errorCode}
 *       做精确分支判断；原始异常的 message 是面向运维的中文描述，会随
 *       调整而变化，不适合做契约。</li>
 *   <li>Controller 不再各自写 try/catch 块；一个 {@code @RestControllerAdvice}
 *       就能把异常统一映射成统一的 JSON 响应体。</li>
 *   <li>stack trace 默认不返回给浏览器；完整的堆栈通过日志落盘便于排查。</li>
 * </ol>
 *
 * <p>使用约定：
 * <ul>
 *   <li>业务校验失败（资源不存在、参数非法）→ 抛 {@code BusinessException}</li>
 *   <li>内部 IO 错误（读文件失败）→ 抛 {@code BusinessException(INTERNAL_IO_ERROR)}</li>
 *   <li>系统错误（数据库无法连接）→ 让 Spring 自动包装为 500 错误，不要走 BusinessException</li>
 * </ul>
 */
public class BusinessException extends IllegalArgumentException {

    private final BusinessErrorCode code;

    public BusinessException(BusinessErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(BusinessErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public BusinessErrorCode getCode() {
        return code;
    }
}
