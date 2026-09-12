package com.bigdata.scriptbox.model;

import java.util.Set;

/**
 * 执行状态枚举。
 *
 * <p>历史原因：数据库 {@code execution_history.status} 列存的是字符串
 * （{@code "RUNNING" / "SUCCESS" / "FAILED" / "TIMEOUT" / "CANCELLED" /
 * "PRECHECK_FAILED"}）。本枚举的 {@link #name()} 严格等于这些字符串，
 * 因此可以直接调用 {@code ExecutionStatus.SUCCESS.name()} 写库。
 *
 * <p>为何引入枚举而不是继续用散落的字符串字面量：
 * <ol>
 *   <li>编译期校验：拼错状态名（如 {@code "SUCCEESS"}）立即报错。</li>
 *   <li>前端 status 字段含义稳定：枚举值就是契约。</li>
 *   <li>IDE 跳转与搜索更友好。</li>
 * </ol>
 *
 * <p>注意：状态语义与 {@code ExecutionHistory.success} 字段有重叠但不同。
 * {@code status} 用于表达「最终结果的分类」（包括超时、取消），而
 * {@code success} 仅表示 exitCode==0 且未超时未取消。
 */
public enum ExecutionStatus {
    /** 已准入、尚未 spawn 进程（PR-0 异步执行引入）。 */
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    CANCELLED,
    PRECHECK_FAILED,
    /**
     * RUNNING 中进程消失后的回填终态：典型场景是服务重启
     * （StartupReconciler 在 {@code ApplicationReadyEvent} 扫描并改写）。
     * 与 CANCELLED 区分在于"是否用户主动取消"——UI 用 interruptedReason 区分。
     */
    INTERRUPTED;

    /** 持久化时使用的字符串集合，方便 Mapper / SQL 校验。 */
    public static final Set<String> ALL = Set.of(
            PENDING.name(),
            RUNNING.name(),
            SUCCESS.name(),
            FAILED.name(),
            TIMEOUT.name(),
            CANCELLED.name(),
            PRECHECK_FAILED.name(),
            INTERRUPTED.name()
    );

    /**
     * 把数据库返回的字符串归一化成枚举；未知值返回 {@code FAILED} 以保证
     * 历史脏数据不会让前端看到 null/空。
     */
    public static ExecutionStatus of(String raw) {
        if (raw == null || raw.isBlank()) return FAILED;
        try {
            return ExecutionStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return FAILED;
        }
    }
}
