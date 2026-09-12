package com.bigdata.scriptbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次脚本执行的历史记录。
 *
 * <p>本表是「执行历史」页面的数据源，也是「按批次」「按场景」汇总时的依据。
 * 字段覆盖三段时间：
 * <ul>
 *   <li>V1 — 基础身份（脚本 / 租户）、状态、耗时；</li>
 *   <li>V1.5 — 批次 / 场景维度、result.json 路径与内容、规范化状态；</li>
 *   <li>V2 — 脚本 SHA-256（用于"脚本已变更"提示）、执行上下文快照（用于历史重跑）。</li>
 * </ul>
 */
@Data
@TableName("execution_history")
public class ExecutionHistory {
    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 脚本 ID。 */
    private Long scriptId;
    /** 脚本名称（执行时快照，避免脚本改名后历史丢失上下文）。 */
    private String scriptName;
    /** 租户 ID。 */
    private Long tenantId;
    /** 租户名称（执行时快照）。 */
    private String tenantName;
    /** 当时的参数 JSON。 */
    private String parametersJson;
    /** 是否成功（旧字段，保留以兼容 V1 接口）。 */
    private Boolean success;
    /** 进程退出码；null 表示尚未退出。 */
    private Integer exitCode;
    /** 是否被超时杀掉。 */
    private Boolean timeout;
    /** 耗时毫秒。 */
    private Long durationMs;
    /** stdout 文件路径。 */
    private String stdoutPath;
    /** stderr 文件路径。 */
    private String stderrPath;
    /** 执行目录（stdout / stderr / artifacts / script-body 都在其下）。 */
    private String executionDir;
    /** 开始时间。 */
    private LocalDateTime startTime;
    /** 结束时间。 */
    private LocalDateTime endTime;

    // ---- V1.5 ----
    /** 批次 ID（来自 BatchService）。 */
    private String batchId;
    /** 批次内行号。 */
    private Integer batchRowIndex;
    /** 场景 ID（来自 ScenarioService）。 */
    private Long scenarioId;
    /** 场景内步骤序号。 */
    private Integer scenarioStepNo;
    /** result.json 文件路径（脚本通过 ARTIFACT_DIR 写出后被复制 / 链接）。 */
    private String resultJsonPath;
    /** 规范化状态：SUCCESS / FAILED / TIMEOUT / PRECHECK_FAILED。 */
    private String status;
    /** result.json 内容（原始 JSON 字符串，可能为 null）。 */
    private String resultJson;
    /** V2: 启动时刻脚本正文的 SHA-256；让前端不计算也能判断"脚本已变更"。 */
    private String scriptSha256;
    /** V2: 执行上下文快照 JSON（参数 + 脚本正文 + 租户 + 风险 + 并发开关），
     *  用于"按原样重跑"，即便脚本已被编辑也不影响。 */
    private String snapshotJson;
    /**
     * V3 (PR-0): 中断原因。典型值为 {@code "process_restart"}（服务重启扫表产生）、
     * {@code "manual_cancel"}（用户主动取消，冗余字段，主要看 CANCELLED 状态）。
     * 业务上主要给 UI 在历史页/任务中心显示"已中断（X）"。
     */
    private String interruptedReason;
    /**
     * V3 (PR-0): 重跑 / 重试关联。历史页"使用当前脚本重跑"或"按快照重跑"、
     * 批量执行"仅重试失败行"提交的新执行会指向原 executionId。
     */
    private Long parentExecutionId;
}