package com.bigdata.scriptbox.model;

/**
 * 清理结果枚举。
 *
 * <p>对应 {@code cleanup_history.result} 列的三个稳定字符串：
 * {@code SUCCESS}（全部成功）/ {@code PARTIAL}（部分失败，剩余跳过）/
 * {@code FAILED}（整体失败，未来扩展）。
 *
 * <p>当前清理执行器（{@code CleanupExecutor}）实际只会产出 SUCCESS 或 PARTIAL；
 * FAILED 留作接口常量，便于日后扩展。
 */
public enum CleanupResult {
    SUCCESS,
    PARTIAL,
    FAILED;

    public static CleanupResult of(String raw) {
        if (raw == null || raw.isBlank()) return SUCCESS;
        try {
            return CleanupResult.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return SUCCESS;
        }
    }
}
