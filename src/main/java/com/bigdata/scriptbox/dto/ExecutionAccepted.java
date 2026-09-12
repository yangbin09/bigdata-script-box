package com.bigdata.scriptbox.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * V3 (PR-0): 异步执行早返响应。
 *
 * <p>当 {@code scriptbox.exec.async-enabled=true} 时，{@code POST /api/executions}
 * 准入后立即返回本 DTO（不阻塞到完成）。前端拿到 {@link #executionId} 后
 * 轮询 {@code GET /api/executions/{id}/state} 拿终态，或者订阅
 * {@code /api/executions/recent-active}。
 *
 * <p>当 {@code async-enabled=false} 时仍走同步路径，响应体是 {@code ExecutionHistory}。
 *
 * <p>字段约定：
 * <ul>
 *   <li>{@link #executionId} — 后续所有操作（state / cancel / stdout / artifacts）的入参；</li>
 *   <li>{@link #status} — 一开始固定为 {@code "PENDING"}，进入执行线程后变 {@code "RUNNING"}；</li>
 *   <li>{@link #message} — 准入被拒绝时（如脚本已禁用、租户已禁用）走 {@code ApiResponse.error}
 *       不会到这里；这里仅放人类可读的"已接收"提示。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ExecutionAccepted {
    /** 分配给本次执行的 executionId；前端据此轮询。 */
    private Long executionId;
    /** 准入后的初始状态，固定 "PENDING"。 */
    private String status;
    /** 人类可读提示；老调用方忽略。 */
    private String message;
}
